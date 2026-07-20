package com.portfolio.compliance.workflow;

import com.portfolio.compliance.rules.RuleSeverity;

public enum RiskSeverity {
    INFO(1),
    LOW(5),
    MEDIUM(12),
    HIGH(25),
    CRITICAL(40);

    private final int weight;

    RiskSeverity(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }

    public static RiskSeverity fromRule(RuleSeverity severity) {
        return switch (severity) {
            case ERROR -> HIGH;
            case WARNING -> MEDIUM;
            case INFO -> INFO;
        };
    }
}
