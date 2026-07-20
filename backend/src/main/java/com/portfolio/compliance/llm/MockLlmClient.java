package com.portfolio.compliance.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.compliance.agent.tool.ToolNames;

/**
 * 离线文本替身：只把确定性工具摘要组织为可预测叙事，不生成审核 findings。
 */
public class MockLlmClient implements LlmClient {

    private final ObjectMapper om;

    public MockLlmClient() {
        this(new ObjectMapper());
    }

    public MockLlmClient(ObjectMapper om) {
        this.om = om;
    }

    @Override
    public String provider() {
        return "mock";
    }

    @Override
    public LlmChatResult chat(LlmChatRequest request) {
        boolean hasToolResult = request.getMessages().stream()
                .anyMatch(m -> "tool".equals(m.getRole()));
        if (hasToolResult) {
            return LlmChatResult.text(composeFinal(request));
        }

        String user = lastUser(request);
        List<String> planned = planTools(user, request.getTools());
        if (planned.isEmpty()) {
            return LlmChatResult.text(composeWithoutTools(user));
        }

        List<ToolCall> calls = planned.stream()
                .map(name -> new ToolCall("call_" + name, name, buildArgs(name, user)))
                .toList();
        return LlmChatResult.tools(calls);
    }

    @Override
    public void chatStream(LlmChatRequest request, Consumer<String> onToken) {
        streamText(composeFinal(request), onToken);
    }

    private List<String> planTools(String user, List<ToolSpec> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        List<String> planned = new java.util.ArrayList<>();
        if (has(tools, ToolNames.CHECK_RULES)) {
            planned.add(ToolNames.CHECK_RULES);
        }
        if (has(tools, ToolNames.COMPARE_CLAUSE)
                && containsAny(user, "对比", "版本", "diff", "变更", "clause")) {
            planned.add(ToolNames.COMPARE_CLAUSE);
        }
        if (has(tools, ToolNames.SEARCH_REGULATION)
                && containsAny(user, "法规", "内规", "条文", "依据")) {
            planned.add(ToolNames.SEARCH_REGULATION);
        }
        if (has(tools, ToolNames.SUMMARIZE_RISKS)) {
            planned.add(ToolNames.SUMMARIZE_RISKS);
        }
        return planned;
    }

    private String buildArgs(String tool, String user) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("doc_id", 0);
        switch (tool) {
            case ToolNames.COMPARE_CLAUSE -> {
                args.put("clause_ref", "争议解决");
                args.put("base_version", "v1");
                args.put("target_version", "v2");
            }
            case ToolNames.SEARCH_REGULATION -> {
                args.put("keyword", "争议解决");
                args.put("top_k", 3);
            }
            case ToolNames.SUMMARIZE_RISKS -> args.put("finding_ids", List.of());
            default -> {
            }
        }
        try {
            return om.writeValueAsString(args);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String composeWithoutTools(String user) {
        return """
                【Mock 文本整理模式】
                未提供确定性工具结果，因此不生成新的审核发现。

                请求摘要：%s
                """.formatted(truncate(user, 120));
    }

    private String composeFinal(LlmChatRequest request) {
        List<String> toolSummaries = request.getMessages().stream()
                .filter(m -> "tool".equals(m.getRole()) && m.getContent() != null)
                .map(ChatMsg::getContent)
                .toList();
        if (!toolSummaries.isEmpty()) {
            return """
                    【Mock 文本整理模式】
                    以下内容仅整理系统已验证的工具结果，不新增事实或法律结论：

                    %s

                    建议：由人工审核人逐项确认风险、依据与整改证据。本结果不替代律师意见。
                    """.formatted(String.join("\n", toolSummaries));
        }
        return composeWithoutTools(lastUser(request));
    }

    private String lastUser(LlmChatRequest request) {
        for (int i = request.getMessages().size() - 1; i >= 0; i--) {
            ChatMsg m = request.getMessages().get(i);
            if ("user".equals(m.getRole())) {
                return m.getContent() == null ? "" : m.getContent();
            }
        }
        return "";
    }

    private boolean has(List<ToolSpec> tools, String name) {
        return tools.stream().anyMatch(t -> t.name().equals(name));
    }

    private boolean containsAny(String text, String... kws) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase();
        for (String kw : kws) {
            if (lower.contains(kw.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private void streamText(String text, Consumer<String> onToken) {
        int step = 4;
        for (int i = 0; i < text.length(); i += step) {
            onToken.accept(text.substring(i, Math.min(text.length(), i + step)));
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
