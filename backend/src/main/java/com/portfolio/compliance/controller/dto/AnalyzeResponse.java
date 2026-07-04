package com.portfolio.compliance.controller.dto;

import java.util.List;

import com.portfolio.compliance.agent.ComplianceFinding;
import com.portfolio.compliance.rules.ComplianceRule;
import com.portfolio.compliance.rules.RuleSeverity;

public record AnalyzeResponse(
        Long documentId,
        RuleSeverity maxSeverity,
        String llmProvider,
        List<ComplianceRule> ruleHits,
        List<ComplianceFinding> findings,
        List<String> toolTrace,
        String llmSummary) {
}
