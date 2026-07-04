package com.portfolio.compliance.controller;

import java.util.Map;

import com.portfolio.compliance.common.ApiResponse;
import com.portfolio.compliance.llm.LlmClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final LlmClient llmClient;

    public HealthController(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "status", "UP",
                "llmProvider", llmClient.provider()));
    }
}
