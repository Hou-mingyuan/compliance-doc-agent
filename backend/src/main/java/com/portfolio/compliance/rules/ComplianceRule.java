package com.portfolio.compliance.rules;

public record ComplianceRule(
        String code,
        String name,
        RuleSeverity severity,
        String message) {
}
