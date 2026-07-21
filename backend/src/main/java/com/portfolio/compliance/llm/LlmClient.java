package com.portfolio.compliance.llm;

import java.util.function.Consumer;

/** 大模型客户端抽象：可插拔 OpenAI 兼容 / 离线 Mock。 */
public interface LlmClient {

    String provider();

    default boolean ready() {
        return true;
    }

    default String diagnostic() {
        return "ready";
    }

    LlmChatResult chat(LlmChatRequest request);

    void chatStream(LlmChatRequest request, Consumer<String> onToken);
}
