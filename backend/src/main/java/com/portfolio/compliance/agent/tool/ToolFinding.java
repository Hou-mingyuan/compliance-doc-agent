package com.portfolio.compliance.agent.tool;

import com.portfolio.compliance.workflow.RiskSeverity;

public record ToolFinding(
        RiskSeverity severity,
        String title,
        String description,
        String sourceType,
        String ruleCode,
        String evidenceText,
        String suggestion,
        Integer matchStart,
        Integer matchEnd,
        double confidence,
        String regulationQuery) {
}
