package com.portfolio.compliance.agent.tool.stub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.portfolio.compliance.agent.ComplianceFinding;
import com.portfolio.compliance.agent.tool.AgentTool;
import com.portfolio.compliance.agent.tool.ToolNames;
import com.portfolio.compliance.agent.tool.ToolResult;
import com.portfolio.compliance.agent.tool.ToolSupport;
import com.portfolio.compliance.llm.ToolSpec;
import com.portfolio.compliance.rules.ComplianceRule;
import com.portfolio.compliance.rules.RuleEngine;
import com.portfolio.compliance.rules.RuleSeverity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 8 个 Function Calling 工具 stub 实现（Mock 模式返回结构化 findings）。 */
public final class ComplianceAgentToolStubs {

    private ComplianceAgentToolStubs() {
    }

    private static Map<String, Object> findingsPayload(List<ComplianceFinding> findings) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("findings", findings);
        data.put("count", findings.size());
        return data;
    }

    private static ComplianceFinding finding(
            String ruleCode, RuleSeverity severity, String message, String source, String excerpt, String suggestion) {
        return new ComplianceFinding(
                "F-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                ruleCode,
                severity,
                message,
                source,
                excerpt,
                suggestion);
    }

    public static final class CheckRulesTool implements AgentTool {

        private final RuleEngine ruleEngine;

        public CheckRulesTool(RuleEngine ruleEngine) {
            this.ruleEngine = ruleEngine;
        }

        @Override
        public String name() {
            return ToolNames.CHECK_RULES;
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec(name(), "对文档正文执行规则包硬校验，返回结构化命中项",
                    ToolSupport.schema(
                            Map.of(
                                    "doc_content", ToolSupport.prop("string", "待审查文档正文"),
                                    "rule_pack_id", ToolSupport.prop("string", "规则包 ID，默认 default")),
                            List.of("doc_content")));
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String content = ToolSupport.str(args, "doc_content");
            if (content == null) {
                return ToolResult.fail("缺少 doc_content 参数。");
            }
            List<ComplianceRule> hits = ruleEngine.evaluate(content);
            List<ComplianceFinding> findings = new ArrayList<>();
            for (ComplianceRule hit : hits) {
                findings.add(finding(
                        hit.code(),
                        hit.severity(),
                        hit.message(),
                        "rule_engine",
                        truncate(content, 80),
                        "请对照规则「" + hit.name() + "」修订相关条款。"));
            }
            String summary = findings.isEmpty()
                    ? "规则引擎未命中风险项。"
                    : "规则引擎命中 " + findings.size() + " 项，最高等级 "
                            + maxSeverity(findings).name();
            return ToolResult.ok(summary, findingsPayload(findings));
        }
    }

    public static final class CompareClauseTool implements AgentTool {

        @Override
        public String name() {
            return ToolNames.COMPARE_CLAUSE;
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec(name(), "对比两个版本文档的指定条款差异",
                    ToolSupport.schema(
                            Map.of(
                                    "doc_id", ToolSupport.prop("string", "文档 ID"),
                                    "clause_ref", ToolSupport.prop("string", "条款编号或关键词"),
                                    "base_version", ToolSupport.prop("string", "基准版本号"),
                                    "target_version", ToolSupport.prop("string", "对比版本号")),
                            List.of("doc_id", "clause_ref")));
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String clauseRef = ToolSupport.str(args, "clause_ref");
            List<ComplianceFinding> findings = List.of(finding(
                    "DIFF-001",
                    RuleSeverity.WARNING,
                    "条款「" + (clauseRef == null ? "争议解决" : clauseRef) + "」在版本间存在表述差异",
                    "compare_clause",
                    "基准版：协商解决；对比版：直接仲裁",
                    "建议统一争议解决方式并做法务确认。"));
            return ToolResult.ok("发现 1 处条款差异（Mock stub）。", findingsPayload(findings));
        }
    }

    public static final class SummarizeRisksTool implements AgentTool {

        @Override
        public String name() {
            return ToolNames.SUMMARIZE_RISKS;
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec(name(), "汇总发现项并计算综合风险等级",
                    ToolSupport.schema(
                            Map.of(
                                    "doc_id", ToolSupport.prop("string", "文档 ID"),
                                    "finding_ids", ToolSupport.prop("array", "待汇总的发现项 ID 列表")),
                            List.of("doc_id")));
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("riskScore", 62);
            data.put("riskLevel", "MEDIUM");
            data.put("highCount", 1);
            data.put("mediumCount", 2);
            data.put("lowCount", 0);
            data.put("summary", "综合风险中等，建议优先处理必备条款缺失与争议解决表述问题。");
            return ToolResult.ok("综合风险等级：MEDIUM（62 分，Mock stub）。", data);
        }
    }

    public static final class SearchRegulationTool implements AgentTool {

        @Override
        public String name() {
            return ToolNames.SEARCH_REGULATION;
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec(name(), "检索法规 / 内规库中的相关条文",
                    ToolSupport.schema(
                            Map.of(
                                    "keyword", ToolSupport.prop("string", "检索关键词"),
                                    "category", ToolSupport.prop("string", "法规类别"),
                                    "top_k", ToolSupport.prop("integer", "返回条数，默认 3")),
                            List.of("keyword")));
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String keyword = ToolSupport.str(args, "keyword");
            Map<String, Object> data = Map.of(
                    "regulations", List.of(
                            Map.of("id", "REG-001", "title", "合同法 · 争议解决", "snippet",
                                    "当事人可以约定仲裁或诉讼方式…", "keyword", keyword == null ? "" : keyword),
                            Map.of("id", "REG-002", "title", "内控手册 · 采购审批", "snippet",
                                    "单笔超过 50 万元须总经理审批…", "keyword", keyword == null ? "" : keyword)));
            return ToolResult.ok("检索到 2 条相关法规（Mock stub）。", data);
        }
    }

    public static final class GetDocumentSectionTool implements AgentTool {

        @Override
        public String name() {
            return ToolNames.GET_DOCUMENT_SECTION;
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec(name(), "按章节或页码获取文档原文片段",
                    ToolSupport.schema(
                            Map.of(
                                    "doc_id", ToolSupport.prop("string", "文档 ID"),
                                    "section_id", ToolSupport.prop("string", "章节编号"),
                                    "page_no", ToolSupport.prop("integer", "页码")),
                            List.of("doc_id")));
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String sectionId = ToolSupport.str(args, "section_id");
            Map<String, Object> data = Map.of(
                    "sectionId", sectionId == null ? "3.2" : sectionId,
                    "text", "第三条 争议解决：本合同履行过程中发生的争议，由双方协商解决…",
                    "pageNo", 3);
            return ToolResult.ok("已获取章节原文（Mock stub）。", data);
        }
    }

    public static final class ExtractEntitiesTool implements AgentTool {

        @Override
        public String name() {
            return ToolNames.EXTRACT_ENTITIES;
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec(name(), "从文档中抽取甲乙方、金额、日期等关键实体",
                    ToolSupport.schema(
                            Map.of("doc_id", ToolSupport.prop("string", "文档 ID"),
                                    "doc_content", ToolSupport.prop("string", "文档正文")),
                            List.of("doc_id")));
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("partyA", "某某科技有限公司");
            data.put("partyB", "某某供应商");
            data.put("amount", "¥1,280,000");
            data.put("effectiveDate", "2026-01-01");
            data.put("jurisdiction", "北京市");
            return ToolResult.ok("已抽取 5 个关键实体（Mock stub）。", data);
        }
    }

    public static final class GenerateAuditReportTool implements AgentTool {

        @Override
        public String name() {
            return ToolNames.GENERATE_AUDIT_REPORT;
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec(name(), "生成审核报告（PDF / Word / Markdown）",
                    ToolSupport.schema(
                            Map.of(
                                    "doc_id", ToolSupport.prop("string", "文档 ID"),
                                    "format", ToolSupport.prop("string", "报告格式：pdf / docx / markdown")),
                            List.of("doc_id", "format")));
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String format = ToolSupport.str(args, "format");
            Map<String, Object> data = Map.of(
                    "reportId", "RPT-" + UUID.randomUUID().toString().substring(0, 8),
                    "format", format == null ? "markdown" : format,
                    "url", "/api/reports/mock-preview.md",
                    "status", "READY");
            return ToolResult.ok("审核报告已生成（Mock stub）。", data);
        }
    }

    public static final class CreateRemediationTaskTool implements AgentTool {

        @Override
        public String name() {
            return ToolNames.CREATE_REMEDIATION_TASK;
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec(name(), "为指定发现项创建整改任务",
                    ToolSupport.schema(
                            Map.of(
                                    "doc_id", ToolSupport.prop("string", "文档 ID"),
                                    "finding_id", ToolSupport.prop("string", "发现项 ID"),
                                    "assignee", ToolSupport.prop("string", "整改负责人")),
                            List.of("doc_id", "finding_id")));
        }

        @Override
        public ToolResult execute(Map<String, Object> args) {
            String findingId = ToolSupport.str(args, "finding_id");
            Map<String, Object> data = Map.of(
                    "taskId", "TASK-" + UUID.randomUUID().toString().substring(0, 8),
                    "findingId", findingId == null ? "F-MOCK001" : findingId,
                    "status", "OPEN",
                    "assignee", ToolSupport.str(args, "assignee"));
            return ToolResult.ok("整改任务已创建（Mock stub）。", data);
        }
    }

    private static RuleSeverity maxSeverity(List<ComplianceFinding> findings) {
        RuleSeverity max = RuleSeverity.INFO;
        for (ComplianceFinding f : findings) {
            if (f.severity().ordinal() > max.ordinal()) {
                max = f.severity();
            }
        }
        return max;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    @Configuration
    public static class Config {

        @Bean
        AgentTool checkRulesTool(RuleEngine ruleEngine) {
            return new CheckRulesTool(ruleEngine);
        }

        @Bean
        AgentTool compareClauseTool() {
            return new CompareClauseTool();
        }

        @Bean
        AgentTool summarizeRisksTool() {
            return new SummarizeRisksTool();
        }

        @Bean
        AgentTool searchRegulationTool() {
            return new SearchRegulationTool();
        }

        @Bean
        AgentTool getDocumentSectionTool() {
            return new GetDocumentSectionTool();
        }

        @Bean
        AgentTool extractEntitiesTool() {
            return new ExtractEntitiesTool();
        }

        @Bean
        AgentTool generateAuditReportTool() {
            return new GenerateAuditReportTool();
        }

        @Bean
        AgentTool createRemediationTaskTool() {
            return new CreateRemediationTaskTool();
        }
    }
}
