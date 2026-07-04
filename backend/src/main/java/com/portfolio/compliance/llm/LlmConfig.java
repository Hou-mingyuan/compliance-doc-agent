package com.portfolio.compliance.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.compliance.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public LlmClient llmClient(AppProperties props, ObjectMapper objectMapper) {
        AppProperties.Llm cfg = props.getLlm();
        if ("openai".equalsIgnoreCase(cfg.getProvider()) && cfg.getApiKey() != null
                && !cfg.getApiKey().isBlank()) {
            log.info("LLM 供应方：openai（model={}, base-url={}）", cfg.getModel(), cfg.getBaseUrl());
            return new OpenAiLlmClient(cfg, objectMapper);
        }
        if ("openai".equalsIgnoreCase(cfg.getProvider())) {
            log.warn("已选择 openai 但未配置 LLM_API_KEY，回退到离线 Mock 模型。");
        } else {
            log.info("LLM 供应方：mock（离线内置，无需密钥）");
        }
        return new MockLlmClient(objectMapper);
    }
}
