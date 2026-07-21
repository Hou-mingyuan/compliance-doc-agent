package com.portfolio.compliance.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Cors cors = new Cors();
    private Security security = new Security();
    private Llm llm = new Llm();
    private Agent agent = new Agent();
    private Rules rules = new Rules();

    @Data
    public static class Cors {
        private String allowedOrigins = "http://localhost:19070,http://127.0.0.1:19070";
    }

    @Data
    public static class Security {
        private boolean demoEnabled = true;
        private int demoBcryptStrength = 8;
        private String demoPassword = "demo-change-me";
        private String adminPassword = "admin-change-me";
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
        private int toolTimeoutSeconds = 3;
    }

    @Data
    public static class Rules {
        private boolean enabled = true;
    }
}
