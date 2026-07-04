package com.portfolio.compliance.llm;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class LlmChatRequest {

    private List<ChatMsg> messages = new ArrayList<>();
    private List<ToolSpec> tools = new ArrayList<>();
    private boolean allowTools = true;

    public LlmChatRequest() {
    }

    public LlmChatRequest(List<ChatMsg> messages) {
        this.messages = messages;
    }

    public LlmChatRequest(List<ChatMsg> messages, List<ToolSpec> tools) {
        this.messages = messages;
        this.tools = tools;
    }
}
