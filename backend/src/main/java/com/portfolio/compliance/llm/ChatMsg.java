package com.portfolio.compliance.llm;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMsg {

    private String role;
    private String content;
    private String name;
    private String toolCallId;
    private List<ToolCall> toolCalls;

    public static ChatMsg system(String content) {
        return new ChatMsg("system", content, null, null, null);
    }

    public static ChatMsg user(String content) {
        return new ChatMsg("user", content, null, null, null);
    }

    public static ChatMsg assistant(String content) {
        return new ChatMsg("assistant", content, null, null, null);
    }

    public static ChatMsg assistantToolCalls(List<ToolCall> toolCalls) {
        return new ChatMsg("assistant", null, null, null, toolCalls);
    }

    public static ChatMsg tool(String toolCallId, String name, String content) {
        return new ChatMsg("tool", content, name, toolCallId, null);
    }
}
