package com.portfolio.compliance.llm;

import java.util.function.Consumer;

import com.portfolio.compliance.common.BizException;

public class UnavailableLlmClient implements LlmClient {

    private final String provider;
    private final String reason;

    public UnavailableLlmClient(String provider, String reason) {
        this.provider = provider;
        this.reason = reason;
    }

    @Override
    public String provider() {
        return provider + "-unavailable";
    }

    @Override
    public boolean ready() {
        return false;
    }

    @Override
    public String diagnostic() {
        return reason;
    }

    @Override
    public LlmChatResult chat(LlmChatRequest request) {
        throw new BizException(503, reason);
    }

    @Override
    public void chatStream(LlmChatRequest request, Consumer<String> onToken) {
        throw new BizException(503, reason);
    }
}
