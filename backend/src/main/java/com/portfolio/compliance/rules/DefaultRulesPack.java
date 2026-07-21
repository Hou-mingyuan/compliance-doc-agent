package com.portfolio.compliance.rules;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DefaultRulesPack(
        String version,
        String packName,
        String description,
        List<RuleDefinition> rules) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RuleDefinition(
            String id,
            String name,
            String severity,
            String mode,
            List<String> documentTypes,
            String pattern) {
    }
}
