package com.portfolio.compliance.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.portfolio.compliance.config.AppProperties;
import com.portfolio.compliance.llm.MockLlmClient;
import com.portfolio.compliance.rules.ComplianceRule;
import com.portfolio.compliance.rules.RuleEngine;
import com.portfolio.compliance.rules.RuleSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ComplianceAgentOrchestratorStreamTest {

    private ComplianceAgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getRules().setEnabled(true);
        orchestrator = new ComplianceAgentOrchestrator(
                new MockLlmClient(),
                new RuleEngine(),
                new com.portfolio.compliance.agent.tool.ToolRegistry(List.of()),
                props,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void analyzeStream_emitsFindingsAndNarrative() {
        String content = "本合同双方同意以下条款……乙方免除全部责任。";
        List<ComplianceRule> findings = new ArrayList<>();
        StringBuilder narrative = new StringBuilder();

        String result = orchestrator.analyzeStream("测试合同", content, new AuditStreamCallbacks() {
            @Override
            public void onFinding(ComplianceRule rule) {
                findings.add(rule);
            }

            @Override
            public void onToken(String token) {
                narrative.append(token);
            }
        });

        assertFalse(findings.isEmpty());
        assertTrue(findings.stream().anyMatch(r -> "R-CON-002".equals(r.code())), "缺失签署条款");
        assertTrue(findings.stream().anyMatch(r -> "R-CON-003".equals(r.code())), "禁止无限连带责任");
        assertFalse(result.isBlank());
        assertFalse(narrative.toString().isBlank());
    }

    @Test
    void analyzeStream_cleanDoc_hasNarrativeOnly() {
        List<ComplianceRule> findings = new ArrayList<>();
        orchestrator.analyzeStream(
                "制度",
                "本制度适用于全体员工。本协议经双方签字盖章后生效。争议解决提交仲裁委员会。"
                        + "双方负有保密义务。投资者需注意相关风险因素。",
                new AuditStreamCallbacks() {
                    @Override
                    public void onFinding(ComplianceRule rule) {
                        findings.add(rule);
                    }

                    @Override
                    public void onToken(String token) {
                        /* accumulate */
                    }
                });
        assertTrue(findings.stream().noneMatch(r -> "R-CON-002".equals(r.code())));
    }
}
