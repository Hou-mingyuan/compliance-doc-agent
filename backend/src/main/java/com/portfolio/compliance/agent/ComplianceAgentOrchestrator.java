package com.portfolio.compliance.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.portfolio.compliance.agent.tool.ToolContext;
import com.portfolio.compliance.agent.tool.ToolFinding;
import com.portfolio.compliance.agent.tool.ToolNames;
import com.portfolio.compliance.agent.tool.ToolRegistry;
import com.portfolio.compliance.agent.tool.ToolResult;
import com.portfolio.compliance.common.BizException;
import com.portfolio.compliance.config.AppProperties;
import com.portfolio.compliance.entity.ComplianceDocument;
import com.portfolio.compliance.knowledge.RegulationCatalog.RegulationMatch;
import com.portfolio.compliance.llm.ChatMsg;
import com.portfolio.compliance.llm.LlmChatRequest;
import com.portfolio.compliance.llm.LlmClient;
import com.portfolio.compliance.rules.ComplianceRule;
import com.portfolio.compliance.rules.ComplianceRuleEngine;
import com.portfolio.compliance.rules.RuleSeverity;
import com.portfolio.compliance.security.ActorContext;
import com.portfolio.compliance.workflow.DocumentLocator;
import com.portfolio.compliance.workflow.ReviewStore;
import com.portfolio.compliance.workflow.ReviewStore.FindingDraft;
import com.portfolio.compliance.workflow.ReviewStore.FindingRecord;
import com.portfolio.compliance.workflow.ReviewStore.ReviewRecord;
import com.portfolio.compliance.workflow.ReviewStore.RiskSummary;
import org.springframework.stereotype.Component;

@Component
public class ComplianceAgentOrchestrator {

    private final LlmClient llm;
    private final ComplianceRuleEngine ruleEngine;
    private final ToolRegistry tools;
    private final ReviewStore reviews;
    private final DocumentLocator locator;
    private final AppProperties props;

    public ComplianceAgentOrchestrator(
            LlmClient llm,
            ComplianceRuleEngine ruleEngine,
            ToolRegistry tools,
            ReviewStore reviews,
            DocumentLocator locator,
            AppProperties props) {
        this.llm = llm;
        this.ruleEngine = ruleEngine;
        this.tools = tools;
        this.reviews = reviews;
        this.locator = locator;
        this.props = props;
    }

    public AuditOutcome runReview(
            ReviewRecord review,
            ComplianceDocument document,
            ActorContext actor,
            AuditStreamCallbacks callbacks) {
        ToolContext context = new ToolContext(
                actor, review.id(), document.getId(), document.getDocType(), document.getTitle(), document.getContent());
        List<String> toolSummaries = new ArrayList<>();
        callbacks.onStage("RULES", "执行规则包 " + ruleEngine.packVersion());

        ToolResult ruleResult = execute(
                ToolNames.CHECK_RULES,
                Map.of("doc_id", document.getId(), "rule_pack_id", "default"),
                context,
                callbacks,
                toolSummaries);
        List<ToolFinding> toolFindings = extractList(ruleResult, "findings", ToolFinding.class);
        List<FindingRecord> persisted = new ArrayList<>();
        for (ToolFinding finding : toolFindings) {
            checkCancelled(callbacks);
            FindingRecord saved = reviews.saveFinding(
                    review.id(), document,
                    new FindingDraft(
                            finding.severity(), finding.title(), finding.description(), finding.sourceType(),
                            finding.ruleCode(), finding.evidenceText(), finding.suggestion(),
                            locator.locate(document.getId(), finding.matchStart(), finding.matchEnd()),
                            finding.confidence()));
            persisted.add(saved);
            callbacks.onFinding(saved);

            if (saved.chunkId() != null) {
                execute(ToolNames.GET_DOCUMENT_SECTION,
                        Map.of("doc_id", document.getId(), "chunk_id", saved.chunkId()),
                        context, callbacks, toolSummaries);
            }
            if (finding.regulationQuery() != null && !finding.regulationQuery().isBlank()) {
                ToolResult search = execute(
                        ToolNames.SEARCH_REGULATION,
                        Map.of(
                                "keyword", finding.regulationQuery(),
                                "scope", document.getDocType(),
                                "as_of", java.time.LocalDate.now().toString(),
                                "top_k", 3),
                        context, callbacks, toolSummaries);
                for (RegulationMatch match : extractList(search, "matches", RegulationMatch.class)) {
                    reviews.linkRegulation(saved.id(), match);
                }
            }
        }

        checkCancelled(callbacks);
        callbacks.onStage("ENTITIES", "抽取关键实体并保留原文 span");
        execute(ToolNames.EXTRACT_ENTITIES, Map.of("doc_id", document.getId()),
                context, callbacks, toolSummaries);

        if (document.getVersionNo() != null && document.getVersionNo() > 1) {
            callbacks.onStage("COMPARE", "比较上一文档版本");
            execute(ToolNames.COMPARE_CLAUSE, Map.of("doc_id", document.getId()),
                    context, callbacks, toolSummaries);
        }

        checkCancelled(callbacks);
        callbacks.onStage("SUMMARY", "按持久化风险计算综合分");
        ToolResult summaryResult = execute(
                ToolNames.SUMMARIZE_RISKS,
                Map.of("review_id", review.id()),
                context,
                callbacks,
                toolSummaries);
        RiskSummary summary = summaryResult.data() instanceof RiskSummary value
                ? value
                : reviews.summarize(review.id());

        callbacks.onStage("NARRATIVE", "Mock/LLM 仅整理工具结果");
        String narrative = streamNarrative(document, review, toolSummaries, callbacks);
        return new AuditOutcome(summary, narrative, List.copyOf(persisted), List.copyOf(toolSummaries));
    }

    /** Legacy text analysis stays deterministic and never fabricates tool findings. */
    public AgentAnalysisResult analyze(String title, String content, String documentType) {
        if (content == null || content.isBlank()) {
            throw new BizException("文档内容不能为空");
        }
        if (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > com.portfolio.compliance.parser.DocumentParser.MAX_BYTES) {
            throw new BizException(413, "文档内容不能超过 5MB");
        }
        List<ComplianceRule> ruleHits = props.getRules().isEnabled()
                ? ruleEngine.evaluate(content, documentType) : List.of();
        RuleSeverity max = ruleHits.stream().map(ComplianceRule::severity)
                .max(java.util.Comparator.comparingInt(Enum::ordinal)).orElse(RuleSeverity.INFO);
        String summary = "确定性文本分析命中 %d 项；完整工具链请使用文档审核流程。".formatted(ruleHits.size());
        return new AgentAnalysisResult(ruleHits, List.of(), List.of(), summary, max, llm.provider());
    }

    private ToolResult execute(
            String name,
            Map<String, Object> args,
            ToolContext context,
            AuditStreamCallbacks callbacks,
            List<String> summaries) {
        checkCancelled(callbacks);
        Map<String, Object> authoritative = new LinkedHashMap<>(args);
        if (authoritative.containsKey("doc_id")) {
            authoritative.put("doc_id", context.documentId());
        }
        if (context.reviewId() != null && authoritative.containsKey("review_id")) {
            authoritative.put("review_id", context.reviewId());
        }
        ToolResult result = tools.execute(name, authoritative, context);
        callbacks.onTool(name, result);
        summaries.add(name + " [" + result.code() + "]: " + result.summary());
        if (!result.ok() && !"NO_BASE_VERSION".equals(result.code()) && !"SECTION_NOT_FOUND".equals(result.code())) {
            throw new BizException(422, "工具「" + name + "」失败：" + result.summary());
        }
        return result;
    }

    private String streamNarrative(
            ComplianceDocument document,
            ReviewRecord review,
            List<String> toolSummaries,
            AuditStreamCallbacks callbacks) {
        List<ChatMsg> messages = new ArrayList<>();
        messages.add(ChatMsg.system(props.getAgent().getSystemPrompt()));
        messages.add(ChatMsg.user("""
                请将以下确定性工具结果整理为简短的人工复核说明。
                文档：%s；类型：%s；版本：%s；审核运行：%s。
                不得新增工具未返回的事实、法规或法律结论。
                """.formatted(document.getTitle(), document.getDocType(), document.getVersionNo(), review.reviewKey())));
        int index = 0;
        for (String summary : toolSummaries) {
            messages.add(ChatMsg.tool("deterministic-" + index, "verified_tool", summary));
            index++;
        }
        StringBuilder narrative = new StringBuilder();
        llm.chatStream(new LlmChatRequest(messages, List.of()), token -> {
            checkCancelled(callbacks);
            narrative.append(token);
            callbacks.onToken(token);
        });
        return narrative.toString();
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> extractList(ToolResult result, String key, Class<T> type) {
        if (!result.ok() || !(result.data() instanceof Map<?, ?> map) || !(map.get(key) instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private static void checkCancelled(AuditStreamCallbacks callbacks) {
        if (callbacks.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new ReviewCancelledException();
        }
    }

    public record AuditOutcome(
            RiskSummary riskSummary,
            String narrative,
            List<FindingRecord> findings,
            List<String> toolTrace) {
    }

    public record AgentAnalysisResult(
            List<ComplianceRule> ruleHits,
            List<ComplianceFinding> findings,
            List<String> toolTrace,
            String llmSummary,
            RuleSeverity maxSeverity,
            String llmProvider) {
    }

    public static class ReviewCancelledException extends RuntimeException {
        public ReviewCancelledException() {
            super("审核已取消");
        }
    }
}
