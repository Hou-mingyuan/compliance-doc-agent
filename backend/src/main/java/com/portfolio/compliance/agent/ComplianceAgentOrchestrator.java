package com.portfolio.compliance.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.compliance.agent.tool.ToolRegistry;
import com.portfolio.compliance.agent.tool.ToolResult;
import com.portfolio.compliance.config.AppProperties;
import com.portfolio.compliance.llm.ChatMsg;
import com.portfolio.compliance.llm.LlmChatRequest;
import com.portfolio.compliance.llm.LlmChatResult;
import com.portfolio.compliance.llm.LlmClient;
import com.portfolio.compliance.llm.ToolCall;
import com.portfolio.compliance.llm.ToolSpec;
import com.portfolio.compliance.rules.ComplianceRule;
import com.portfolio.compliance.rules.RuleEngine;
import com.portfolio.compliance.rules.RuleSeverity;
import org.springframework.stereotype.Component;

/** 合规审查 Agent 编排：规则引擎初筛 + Function Calling 工具链 + LLM 深度分析。 */
@Component
public class ComplianceAgentOrchestrator {

    private static final int MAX_TOOL_ROUNDS = 5;

    private final LlmClient llm;
    private final RuleEngine ruleEngine;
    private final ToolRegistry toolRegistry;
    private final AppProperties props;
    private final ObjectMapper om;

    public ComplianceAgentOrchestrator(
            LlmClient llm,
            RuleEngine ruleEngine,
            ToolRegistry toolRegistry,
            AppProperties props,
            ObjectMapper om) {
        this.llm = llm;
        this.ruleEngine = ruleEngine;
        this.toolRegistry = toolRegistry;
        this.props = props;
        this.om = om;
    }

    public AgentAnalysisResult analyze(String title, String content) {
        List<ComplianceRule> ruleHits = props.getRules().isEnabled()
                ? ruleEngine.evaluate(content)
                : List.of();

        List<ChatMsg> messages = new ArrayList<>(List.of(
                ChatMsg.system(props.getAgent().getSystemPrompt()),
                ChatMsg.user(buildUserPrompt(title, content, formatRuleSummary(ruleHits)))));

        List<ToolSpec> toolSpecs = toolRegistry.specs();
        List<ComplianceFinding> findings = new ArrayList<>();
        List<String> toolTrace = new ArrayList<>();

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            LlmChatRequest request = new LlmChatRequest(messages, toolSpecs);
            request.setAllowTools(round < MAX_TOOL_ROUNDS - 1);
            LlmChatResult plan = llm.chat(request);

            if (!plan.hasToolCalls()) {
                RuleSeverity maxSeverity = maxSeverity(ruleHits, findings);
                return new AgentAnalysisResult(
                        ruleHits,
                        findings,
                        toolTrace,
                        plan.content() == null ? "" : plan.content(),
                        maxSeverity,
                        llm.provider());
            }

            messages.add(ChatMsg.assistantToolCalls(plan.toolCalls()));
            for (ToolCall call : plan.toolCalls()) {
                Map<String, Object> args = parseArgs(call.arguments());
                args.putIfAbsent("doc_content", content);
                args.putIfAbsent("doc_title", title);

                ToolResult tr = toolRegistry.execute(call.name(), args);
                toolTrace.add(call.name() + ": " + tr.summary());
                collectFindings(tr, findings);

                messages.add(ChatMsg.tool(call.id(), call.name(), tr.summary()));
            }
        }

        RuleSeverity maxSeverity = maxSeverity(ruleHits, findings);
        return new AgentAnalysisResult(
                ruleHits,
                findings,
                toolTrace,
                "工具调用轮次已达上限，请查看 findings 与 toolTrace。",
                maxSeverity,
                llm.provider());
    }

    /** SSE 流式审核：逐条推送规则命中，流式输出 LLM narrative。 */
    public String analyzeStream(String title, String content, AuditStreamCallbacks callbacks) {
        List<ComplianceRule> ruleHits = props.getRules().isEnabled()
                ? ruleEngine.evaluate(content)
                : List.of();
        for (ComplianceRule rule : ruleHits) {
            callbacks.onFinding(rule);
        }

        LlmChatRequest request = new LlmChatRequest(List.of(
                ChatMsg.system(props.getAgent().getSystemPrompt()),
                ChatMsg.user(buildUserPrompt(title, content, formatRuleSummary(ruleHits)))));

        StringBuilder narrative = new StringBuilder();
        llm.chatStream(request, token -> {
            narrative.append(token);
            callbacks.onToken(token);
        });
        return narrative.toString();
    }

    @SuppressWarnings("unchecked")
    private void collectFindings(ToolResult result, List<ComplianceFinding> findings) {
        if (!result.ok() || !(result.data() instanceof Map<?, ?> data)) {
            return;
        }
        Object raw = data.get("findings");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof ComplianceFinding f) {
                    findings.add(f);
                }
            }
        }
    }

    private Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return om.readValue(json, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private String buildUserPrompt(String title, String content, String ruleSummary) {
        return """
                请审查以下企业文档的合规风险。可调用已注册工具（check_rules / compare_clause / summarize_risks 等）获取结构化发现项。

                【文档标题】%s

                【规则引擎初筛结果】
                %s

                【文档正文】
                %s
                """.formatted(title, ruleSummary, content);
    }

    private String formatRuleSummary(List<ComplianceRule> rules) {
        if (rules.isEmpty()) {
            return "（无规则命中）";
        }
        return rules.stream()
                .map(r -> "- [%s] %s：%s".formatted(r.severity(), r.code(), r.message()))
                .collect(Collectors.joining("\n"));
    }

    private RuleSeverity maxSeverity(List<ComplianceRule> rules, List<ComplianceFinding> findings) {
        RuleSeverity max = RuleSeverity.INFO;
        for (ComplianceRule r : rules) {
            if (r.severity().ordinal() > max.ordinal()) {
                max = r.severity();
            }
        }
        for (ComplianceFinding f : findings) {
            if (f.severity().ordinal() > max.ordinal()) {
                max = f.severity();
            }
        }
        return max;
    }

    public record AgentAnalysisResult(
            List<ComplianceRule> ruleHits,
            List<ComplianceFinding> findings,
            List<String> toolTrace,
            String llmSummary,
            RuleSeverity maxSeverity,
            String llmProvider) {
    }
}
