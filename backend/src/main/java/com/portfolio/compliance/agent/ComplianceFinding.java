package com.portfolio.compliance.agent;

import com.portfolio.compliance.rules.RuleSeverity;

/** Agent / 工具产出的结构化合规发现项。 */
public record ComplianceFinding(
        String id,
        String ruleCode,
        RuleSeverity severity,
        String message,
        String source,
        String excerpt,
        String suggestion) {
}
