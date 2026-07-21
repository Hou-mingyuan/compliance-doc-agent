package com.portfolio.compliance.rules;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** 基于 rules/default-rules.json 的可配置合规规则引擎。 */
@Component
public class RuleEngine implements ComplianceRuleEngine {

    private static final String RULES_RESOURCE = "rules/default-rules.json";
    private static final String EMPTY_CONTENT_CODE = "EMPTY_CONTENT";

    private final List<LoadedRule> rules;
    private final String packVersion;

    public RuleEngine() {
        LoadedPack pack = loadRules();
        this.rules = pack.rules();
        this.packVersion = pack.version();
    }

    @Override
    public List<ComplianceRule> evaluate(String content) {
        return evaluate(content, "CONTRACT");
    }

    @Override
    public List<ComplianceRule> evaluate(String content, String documentType) {
        List<ComplianceRule> hits = new ArrayList<>();
        if (content == null || content.isBlank()) {
            hits.add(new ComplianceRule(
                    EMPTY_CONTENT_CODE,
                    "空文档",
                    RuleSeverity.ERROR,
                    "文档内容为空，无法进行合规审查。"));
            return hits;
        }

        for (LoadedRule rule : rules) {
            if (!rule.appliesTo(documentType)) {
                continue;
            }
            MatchSpan span = rule.matchSpan(content);
            if (span != null) {
                hits.add(new ComplianceRule(
                        rule.id(),
                        rule.name(),
                        rule.severity(),
                        rule.message(),
                        span.start(),
                        span.end(),
                        span.text()));
            }
        }
        return hits;
    }

    @Override
    public int ruleCount() {
        return rules.size();
    }

    @Override
    public String packVersion() {
        return packVersion;
    }

    private LoadedPack loadRules() {
        try (InputStream in = new ClassPathResource(RULES_RESOURCE).getInputStream()) {
            DefaultRulesPack pack = new ObjectMapper().readValue(in, DefaultRulesPack.class);
            List<LoadedRule> loaded = new ArrayList<>();
            for (DefaultRulesPack.RuleDefinition def : pack.rules()) {
                loaded.add(LoadedRule.from(def));
            }
            return new LoadedPack(pack.version(), List.copyOf(loaded));
        } catch (IOException e) {
            throw new IllegalStateException("无法加载内置规则包: " + RULES_RESOURCE, e);
        }
    }

    private record LoadedRule(
            String id,
            String name,
            RuleSeverity severity,
            String message,
            Pattern pattern,
            boolean missingKeyword,
            List<String> documentTypes) {

        static LoadedRule from(DefaultRulesPack.RuleDefinition def) {
            boolean missing = "MISSING".equalsIgnoreCase(def.mode())
                    || (def.mode() == null && def.name() != null && def.name().contains("缺失"));
            return new LoadedRule(
                    def.id(),
                    def.name(),
                    mapSeverity(def.severity()),
                    buildMessage(def.name(), missing),
                    Pattern.compile(def.pattern(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
                    missing,
                    def.documentTypes() == null ? List.of() : def.documentTypes().stream()
                            .map(v -> v.toUpperCase(java.util.Locale.ROOT))
                            .toList());
        }

        boolean appliesTo(String documentType) {
            if (documentTypes.isEmpty()) {
                return true;
            }
            String normalized = documentType == null ? "GENERAL" : documentType.toUpperCase(java.util.Locale.ROOT);
            return documentTypes.contains(normalized);
        }

        boolean matches(String content) {
            return matchSpan(content) != null;
        }

        MatchSpan matchSpan(String content) {
            if (missingKeyword) {
                return pattern.matcher(content).find() ? null : new MatchSpan(-1, -1, null);
            }
            var matcher = pattern.matcher(content);
            if (!matcher.find()) {
                return null;
            }
            return new MatchSpan(matcher.start(), matcher.end(), matcher.group());
        }

        private static RuleSeverity mapSeverity(String severity) {
            if (severity == null) {
                return RuleSeverity.WARNING;
            }
            return switch (severity.toUpperCase()) {
                case "HIGH" -> RuleSeverity.ERROR;
                case "LOW" -> RuleSeverity.INFO;
                default -> RuleSeverity.WARNING;
            };
        }

        private static String buildMessage(String name, boolean missing) {
            if (missing) {
                return "未检测到「" + name.replace("缺失", "").replace("制度", "") + "」相关表述，请补充相应条款。";
            }
            return "检测到「" + name + "」相关风险表述，建议法务复核。";
        }
    }

    private record MatchSpan(int start, int end, String text) {
    }

    private record LoadedPack(String version, List<LoadedRule> rules) {
    }
}
