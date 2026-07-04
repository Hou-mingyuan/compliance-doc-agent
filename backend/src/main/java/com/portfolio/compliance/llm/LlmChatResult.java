package com.portfolio.compliance.llm;

import java.util.List;

public record LlmChatResult(String content, List<ToolCall> toolCalls, String finishReason) {

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public static LlmChatResult text(String content) {
        return new LlmChatResult(content, List.of(), "stop");
    }

    public static LlmChatResult tools(List<ToolCall> toolCalls) {
        return new LlmChatResult(null, toolCalls, "tool_calls");
    }
}
