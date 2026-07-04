package com.portfolio.compliance.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Cors cors = new Cors();
    private Llm llm = new Llm();
    private Agent agent = new Agent();
    private Rules rules = new Rules();

    @Data
    public static class Cors {
        private String allowedOrigins = "*";
    }

    @Data
    public static class Llm {
        private String provider = "mock";
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private String model = "gpt-4o-mini";
        private double temperature = 0.2;
        private int timeoutSeconds = 90;
    }

    @Data
    public static class Agent {
        private String systemPrompt = "你是企业合规文档审查助手。";
    }

    @Data
    public static class Rules {
        private boolean enabled = true;
    }
}
