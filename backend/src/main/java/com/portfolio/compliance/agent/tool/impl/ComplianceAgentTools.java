package com.portfolio.compliance.agent.tool.impl;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.portfolio.compliance.agent.tool.AgentTool;
import com.portfolio.compliance.agent.tool.ToolContext;
import com.portfolio.compliance.agent.tool.ToolFinding;
import com.portfolio.compliance.agent.tool.ToolNames;
import com.portfolio.compliance.agent.tool.ToolResult;
import com.portfolio.compliance.agent.tool.ToolSupport;
import com.portfolio.compliance.analysis.ClauseComparisonService;
import com.portfolio.compliance.analysis.EntityExtractor;
import com.portfolio.compliance.common.BizException;
import com.portfolio.compliance.entity.ComplianceDocument;
import com.portfolio.compliance.entity.ComplianceDocumentChunk;
import com.portfolio.compliance.knowledge.RegulationCatalog;
import com.portfolio.compliance.llm.ToolSpec;
import com.portfolio.compliance.mapper.ComplianceDocumentChunkMapper;
import com.portfolio.compliance.mapper.ComplianceDocumentMapper;
import com.portfolio.compliance.report.ReportService;
import com.portfolio.compliance.rules.ComplianceRule;
import com.portfolio.compliance.rules.ComplianceRuleEngine;
import com.portfolio.compliance.security.AppRole;
import com.portfolio.compliance.service.DocumentUploadService;
import com.portfolio.compliance.workflow.DocumentLocator;
import com.portfolio.compliance.workflow.RemediationService;
import com.portfolio.compliance.workflow.ReviewStore;
import com.portfolio.compliance.workflow.RiskSeverity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

public final class ComplianceAgentTools {

    private ComplianceAgentTools() {
    }

    private abstract static class BaseTool implements AgentTool {
        private final String name;
        private final String description;
        private final Map<String, Object> properties;
        private final List<String> required;
        private final AppRole minimumRole;

        BaseTool(
                String name,
                String description,
                Map<String, Object> properties,
                List<String> required,
                AppRole minimumRole) {
            this.name = name;
            this.description = description;
            this.properties = properties;
            this.required = required;
            this.minimumRole = minimumRole;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec(name, description, ToolSupport.schema(properties, required));
        }

        @Override
        public AppRole minimumRole() {
            return minimumRole;
        }
    }

    public static final class CheckRulesTool extends BaseTool {
        private final ComplianceRuleEngine rules;

        CheckRulesTool(ComplianceRuleEngine rules) {
            super(ToolNames.CHECK_RULES, "按当前规则包和文档类型执行确定性规则审核",
                    Map.of(
                            "doc_id", ToolSupport.prop("integer", "受信任上下文中的文档 ID"),
                            "rule_pack_id", ToolSupport.prop("string", "规则包 ID，当前为 default")),
                    List.of("doc_id"), AppRole.USER);
            this.rules = rules;
        }

        @Override
        public ToolResult execute(ToolContext context, Map<String, Object> args) {
            List<ComplianceRule> hits = rules.evaluate(context.documentContent(), context.documentType());
            List<ToolFinding> findings = hits.stream().map(hit -> new ToolFinding(
                    RiskSeverity.fromRule(hit.severity()), hit.name(), hit.message(), "RULE", hit.code(),
                    maskSensitive(hit.code(), hit.matchedText()), "请结合规则「" + hit.name() + "」人工复核并修订。",
                    hit.matchStart(), hit.matchEnd(), 1d, regulationQuery(hit))).toList();
            return ToolResult.ok(
                    "规则包 %s 命中 %d 项。".formatted(rules.packVersion(), findings.size()),
                    Map.of("rulePackVersion", rules.packVersion(), "findings", findings, "count", findings.size()));
        }
    }

    public static final class CompareClauseTool extends BaseTool {
        private final ComplianceDocumentMapper documentMapper;
        private final DocumentUploadService documents;
        private final ClauseComparisonService comparisons;

        CompareClauseTool(
                ComplianceDocumentMapper documentMapper,
                DocumentUploadService documents,
                ClauseComparisonService comparisons) {
            super(ToolNames.COMPARE_CLAUSE, "对比当前文档版本与指定/上一版本，输出增删改和风险变化",
                    Map.of(
                            "doc_id", ToolSupport.prop("integer", "当前文档 ID"),
                            "base_document_id", ToolSupport.prop("integer", "基准文档版本 ID，可省略为上一版本"),
                            "clause_keyword", ToolSupport.prop("string", "可选条款关键词")),
                    List.of("doc_id"), AppRole.USER);
            this.documentMapper = documentMapper;
            this.documents = documents;
            this.comparisons = comparisons;
        }

        @Override
        public ToolResult execute(ToolContext context, Map<String, Object> args) {
            ComplianceDocument target = documents.requireDocument(context.documentId(), context.actor());
            Long baseId = ToolSupport.longValue(args, "base_document_id");
            ComplianceDocument base = baseId == null ? previousVersion(target) : documents.requireDocument(baseId, context.actor());
            if (base == null) {
                return ToolResult.fail("NO_BASE_VERSION", "当前文档没有可比较的上一版本");
            }
            if (!base.getTenantId().equals(target.getTenantId())) {
                return ToolResult.fail("TENANT_MISMATCH", "版本不属于同一租户");
            }
            var comparison = comparisons.compare(base, target, ToolSupport.str(args, "clause_keyword"));
            return ToolResult.ok(
                    "版本差异 %d 处，新增风险 %d 项，移除风险 %d 项。".formatted(
                            comparison.deltas().size(), comparison.addedRiskCodes().size(),
                            comparison.removedRiskCodes().size()),
                    comparison);
        }

        private ComplianceDocument previousVersion(ComplianceDocument target) {
            if (target.getVersionNo() == null || target.getVersionNo() <= 1) {
                return null;
            }
            Long root = target.getParentDocumentId() == null ? target.getId() : target.getParentDocumentId();
            return documentMapper.selectOne(new QueryWrapper<ComplianceDocument>()
                    .eq("tenant_id", target.getTenantId())
                    .eq("version_no", target.getVersionNo() - 1)
                    .and(q -> q.eq("id", root).or().eq("parent_document_id", root))
                    .last("LIMIT 1"));
        }
    }

    public static final class SummarizeRisksTool extends BaseTool {
        private final ReviewStore reviews;

        SummarizeRisksTool(ReviewStore reviews) {
            super(ToolNames.SUMMARIZE_RISKS, "基于当前审核运行已持久化风险计算综合分和等级",
                    Map.of("review_id", ToolSupport.prop("integer", "受信任上下文中的审核运行 ID")),
                    List.of("review_id"), AppRole.USER);
            this.reviews = reviews;
        }

        @Override
        public ToolResult execute(ToolContext context, Map<String, Object> args) {
            if (context.reviewId() == null) {
                return ToolResult.fail("REVIEW_REQUIRED", "风险汇总需要真实审核运行");
            }
            var summary = reviews.summarize(context.reviewId());
            return ToolResult.ok(summary.summary(), summary);
        }
    }

    public static final class SearchRegulationTool extends BaseTool {
        private final RegulationCatalog catalog;

        SearchRegulationTool(RegulationCatalog catalog) {
            super(ToolNames.SEARCH_REGULATION, "检索带版本、生效日期、范围和来源的演示法规/内规条目",
                    Map.of(
                            "keyword", ToolSupport.prop("string", "检索关键词"),
                            "scope", ToolSupport.prop("string", "适用范围，默认当前文档类型"),
                            "as_of", ToolSupport.prop("string", "检索日期 YYYY-MM-DD"),
                            "top_k", ToolSupport.prop("integer", "返回 1-10 条，默认 3")),
                    List.of("keyword"), AppRole.USER);
            this.catalog = catalog;
        }

        @Override
        public ToolResult execute(ToolContext context, Map<String, Object> args) {
            String asOf = ToolSupport.str(args, "as_of");
            LocalDate date;
            try {
                date = asOf == null ? LocalDate.now() : LocalDate.parse(asOf);
            } catch (Exception ex) {
                return ToolResult.fail("VALIDATION_ERROR", "as_of 必须是 YYYY-MM-DD");
            }
            String scope = ToolSupport.str(args, "scope");
            List<RegulationCatalog.RegulationMatch> matches = catalog.search(
                    ToolSupport.str(args, "keyword"), scope == null ? context.documentType() : scope,
                    date, ToolSupport.intValue(args, "top_k", 3));
            String summary = matches.isEmpty()
                    ? "演示法规库没有命中，未生成替代性依据。"
                    : "检索到 " + matches.size() + " 条明确标注的演示法规/内规。";
            return ToolResult.ok(summary, Map.of("matches", matches, "count", matches.size(), "demoData", true));
        }
    }

    public static final class GetDocumentSectionTool extends BaseTool {
        private final DocumentUploadService documents;
        private final ComplianceDocumentChunkMapper chunks;

        GetDocumentSectionTool(DocumentUploadService documents, ComplianceDocumentChunkMapper chunks) {
            super(ToolNames.GET_DOCUMENT_SECTION, "按 chunk、页码、章节或段落获取真实文档原文",
                    Map.of(
                            "doc_id", ToolSupport.prop("integer", "当前文档 ID"),
                            "chunk_id", ToolSupport.prop("integer", "可选文本块 ID"),
                            "page_no", ToolSupport.prop("integer", "可选 PDF 页码"),
                            "section_title", ToolSupport.prop("string", "可选章节标题"),
                            "paragraph_no", ToolSupport.prop("integer", "可选段落号")),
                    List.of("doc_id"), AppRole.USER);
            this.documents = documents;
            this.chunks = chunks;
        }

        @Override
        public ToolResult execute(ToolContext context, Map<String, Object> args) {
            documents.requireDocument(context.documentId(), context.actor());
            QueryWrapper<ComplianceDocumentChunk> query = new QueryWrapper<ComplianceDocumentChunk>()
                    .eq("document_id", context.documentId());
            Long chunkId = ToolSupport.longValue(args, "chunk_id");
            if (chunkId != null) query.eq("id", chunkId);
            Integer pageNo = ToolSupport.intValue(args, "page_no", -1);
            if (pageNo > 0) query.eq("page_no", pageNo);
            String section = ToolSupport.str(args, "section_title");
            if (section != null) query.like("section_title", section);
            Integer paragraph = ToolSupport.intValue(args, "paragraph_no", -1);
            if (paragraph > 0) query.eq("paragraph_no", paragraph);
            query.orderByAsc("chunk_index").last("LIMIT 20");
            List<ComplianceDocumentChunk> results = chunks.selectList(query);
            if (results.isEmpty()) {
                return ToolResult.fail("SECTION_NOT_FOUND", "没有找到符合定位条件的原文片段");
            }
            return ToolResult.ok("获取到 " + results.size() + " 个真实原文片段。", Map.of(
                    "sections", results.stream().map(row -> Map.of(
                            "chunkId", row.getId(),
                            "pageNo", row.getPageNo() == null ? 0 : row.getPageNo(),
                            "sectionTitle", row.getSectionTitle() == null ? "" : row.getSectionTitle(),
                            "paragraphNo", row.getParagraphNo() == null ? 0 : row.getParagraphNo(),
                            "charStart", row.getCharStart(),
                            "charEnd", row.getCharEnd(),
                            "text", row.getContent())).toList()));
        }
    }

    public static final class ExtractEntitiesTool extends BaseTool {
        private final EntityExtractor extractor;
        private final ReviewStore reviews;
        private final DocumentLocator locator;
        private final DocumentUploadService documents;

        ExtractEntitiesTool(
                EntityExtractor extractor,
                ReviewStore reviews,
                DocumentLocator locator,
                DocumentUploadService documents) {
            super(ToolNames.EXTRACT_ENTITIES, "抽取主体、金额、日期、责任、自动续期等实体并保留 span",
                    Map.of("doc_id", ToolSupport.prop("integer", "当前文档 ID")),
                    List.of("doc_id"), AppRole.USER);
            this.extractor = extractor;
            this.reviews = reviews;
            this.locator = locator;
            this.documents = documents;
        }

        @Override
        public ToolResult execute(ToolContext context, Map<String, Object> args) {
            ComplianceDocument doc = documents.requireDocument(context.documentId(), context.actor());
            var extracted = extractor.extract(context.documentContent());
            Object data;
            if (context.reviewId() == null) {
                data = extracted;
            } else {
                data = extracted.stream().map(entity -> reviews.saveEntity(
                        context.reviewId(), doc, entity.type(), entity.value(), entity.value(),
                        entity.start(), entity.end(), entity.confidence(),
                        locator.locate(doc.getId(), entity.start(), entity.end()))).toList();
            }
            return ToolResult.ok("从文档输入中抽取到 " + extracted.size() + " 个实体。",
                    Map.of("entities", data, "count", extracted.size()));
        }
    }

    public static final class GenerateAuditReportTool extends BaseTool {
        private final ReviewStore reviews;
        private final ReportService reports;

        GenerateAuditReportTool(ReviewStore reviews, ReportService reports) {
            super(ToolNames.GENERATE_AUDIT_REPORT, "从持久化审核快照生成真实 DOCX 报告",
                    Map.of(
                            "review_id", ToolSupport.prop("integer", "审核运行 ID"),
                            "format", ToolSupport.prop("string", "固定为 docx")),
                    List.of("review_id", "format"), AppRole.REVIEWER);
            this.reviews = reviews;
            this.reports = reports;
        }

        @Override
        public int timeoutSeconds(int configuredDefault) {
            return Math.max(configuredDefault, 10);
        }

        @Override
        public ToolResult execute(ToolContext context, Map<String, Object> args) {
            if (!"docx".equalsIgnoreCase(ToolSupport.str(args, "format"))) {
                return ToolResult.fail("UNSUPPORTED_FORMAT", "v1 仅支持经过渲染验收的 DOCX 报告");
            }
            if (context.reviewId() == null) {
                return ToolResult.fail("REVIEW_REQUIRED", "报告生成需要真实审核运行");
            }
            String reviewKey = reviews.requireReview(context.reviewId(), context.actor()).reviewKey();
            var report = reports.generate(reviewKey, context.actor());
            return ToolResult.ok("DOCX 报告已生成并持久化。", report);
        }
    }

    public static final class CreateRemediationTaskTool extends BaseTool {
        private final RemediationService remediations;

        CreateRemediationTaskTool(RemediationService remediations) {
            super(ToolNames.CREATE_REMEDIATION_TASK, "为已确认风险创建真实、幂等的整改任务",
                    Map.of(
                            "finding_key", ToolSupport.prop("string", "已确认风险业务 ID"),
                            "assignee", ToolSupport.prop("string", "整改负责人用户 ID"),
                            "due_date", ToolSupport.prop("string", "截止日期 YYYY-MM-DD"),
                            "description", ToolSupport.prop("string", "整改要求")),
                    List.of("finding_key", "assignee", "due_date", "description"),
                    AppRole.COMPLIANCE_ADMIN);
            this.remediations = remediations;
        }

        @Override
        public ToolResult execute(ToolContext context, Map<String, Object> args) {
            LocalDate due;
            try {
                due = LocalDate.parse(ToolSupport.str(args, "due_date"));
            } catch (Exception ex) {
                return ToolResult.fail("VALIDATION_ERROR", "due_date 必须是 YYYY-MM-DD");
            }
            var task = remediations.create(
                    context.actor(), ToolSupport.str(args, "finding_key"), ToolSupport.str(args, "assignee"),
                    due, ToolSupport.str(args, "description"));
            return ToolResult.ok("整改任务已持久化：" + task.taskKey(), task);
        }
    }

    private static String regulationQuery(ComplianceRule rule) {
        return switch (rule.code()) {
            case "R-CON-001" -> "争议解决 仲裁 诉讼";
            case "R-CON-003" -> "责任 免责 放弃追索";
            case "R-PII-001" -> "个人信息 身份证 最小必要";
            case "R-POL-001" -> "采购 适用范围 审批";
            case "R-CON-006" -> "解除 责任";
            default -> rule.name();
        };
    }

    private static String maskSensitive(String ruleCode, String text) {
        if (text == null) return "全文未检测到对应必备表述";
        if ("R-PII-001".equals(ruleCode) && text.length() >= 8) {
            return text.substring(0, 4) + "**********" + text.substring(text.length() - 4);
        }
        return text;
    }

    @Configuration
    public static class Config {
        @Bean AgentTool checkRulesTool(ComplianceRuleEngine rules) { return new CheckRulesTool(rules); }
        @Bean AgentTool compareClauseTool(ComplianceDocumentMapper mapper, DocumentUploadService documents,
                ClauseComparisonService comparisons) { return new CompareClauseTool(mapper, documents, comparisons); }
        @Bean AgentTool summarizeRisksTool(ReviewStore reviews) { return new SummarizeRisksTool(reviews); }
        @Bean AgentTool searchRegulationTool(RegulationCatalog catalog) { return new SearchRegulationTool(catalog); }
        @Bean AgentTool getDocumentSectionTool(DocumentUploadService documents,
                ComplianceDocumentChunkMapper chunks) { return new GetDocumentSectionTool(documents, chunks); }
        @Bean AgentTool extractEntitiesTool(EntityExtractor extractor, ReviewStore reviews,
                DocumentLocator locator, DocumentUploadService documents) {
            return new ExtractEntitiesTool(extractor, reviews, locator, documents);
        }
        @Bean AgentTool generateAuditReportTool(ReviewStore reviews, ReportService reports) {
            return new GenerateAuditReportTool(reviews, reports);
        }
        @Bean AgentTool createRemediationTaskTool(RemediationService remediations) {
            return new CreateRemediationTaskTool(remediations);
        }
    }
}
