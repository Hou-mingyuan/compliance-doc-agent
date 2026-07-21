package com.portfolio.compliance.controller;

import java.util.Map;

import com.portfolio.compliance.common.ApiResponse;
import com.portfolio.compliance.llm.LlmClient;
import com.portfolio.compliance.rules.ComplianceRuleEngine;
import com.portfolio.compliance.config.AppProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final LlmClient llmClient;
    private final ComplianceRuleEngine rules;
    private final AppProperties props;

    public HealthController(LlmClient llmClient, ComplianceRuleEngine rules, AppProperties props) {
        this.llmClient = llmClient;
        this.rules = rules;
        this.props = props;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "status", llmClient.ready() ? "UP" : "DEGRADED",
                "llmProvider", llmClient.provider(),
                "llmReady", llmClient.ready(),
                "llmDiagnostic", llmClient.diagnostic(),
                "rulePackVersion", rules.packVersion(),
                "ruleCount", rules.ruleCount(),
                "demoAuth", props.getSecurity().isDemoEnabled()));
    }
}
