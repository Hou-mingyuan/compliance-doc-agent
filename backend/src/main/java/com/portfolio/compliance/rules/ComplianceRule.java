package com.portfolio.compliance.rules;

public record ComplianceRule(
        String code,
        String name,
        RuleSeverity severity,
        String message,
        Integer matchStart,
        Integer matchEnd,
        String matchedText) {

    public ComplianceRule(String code, String name, RuleSeverity severity, String message) {
        this(code, name, severity, message, null, null, null);
    }
}
