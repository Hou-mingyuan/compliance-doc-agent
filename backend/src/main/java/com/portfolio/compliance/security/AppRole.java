package com.portfolio.compliance.security;

public enum AppRole {
    USER,
    REVIEWER,
    COMPLIANCE_ADMIN,
    SYSTEM_ADMIN;

    public boolean atLeast(AppRole required) {
        return ordinal() >= required.ordinal();
    }
}
