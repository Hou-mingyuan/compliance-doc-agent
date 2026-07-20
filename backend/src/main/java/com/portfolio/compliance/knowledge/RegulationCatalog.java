package com.portfolio.compliance.knowledge;

import java.io.InputStream;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.compliance.common.BizException;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class RegulationCatalog {

    private static final String RESOURCE = "regulations/demo-regulations.json";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public RegulationCatalog(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void seedDemoCatalog() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            List<SeedEntry> entries = objectMapper.readValue(in, new TypeReference<>() {
            });
            for (SeedEntry entry : entries) {
                int updated = jdbc.update("""
                                UPDATE regulation_entry
                                SET title = ?, version_label = ?, effective_date = ?, expiry_date = ?, scope = ?,
                                    source_name = ?, source_url = ?, article_no = ?, content = ?, keywords = ?,
                                    demo_data = TRUE, active = TRUE, updated_at = CURRENT_TIMESTAMP
                                WHERE code = ?
                                """,
                        entry.title(), entry.versionLabel(), Date.valueOf(entry.effectiveDate()),
                        entry.expiryDate() == null ? null : Date.valueOf(entry.expiryDate()),
                        entry.scope(), entry.sourceName(), entry.sourceUrl(), entry.articleNo(),
                        entry.content(), entry.keywords(), entry.code());
                if (updated == 0) {
                    jdbc.update("""
                                INSERT INTO regulation_entry
                                (code, title, version_label, effective_date, expiry_date, scope, source_name,
                                 source_url, article_no, content, keywords, demo_data, active)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, TRUE)
                                """,
                            entry.code(), entry.title(), entry.versionLabel(), Date.valueOf(entry.effectiveDate()),
                            entry.expiryDate() == null ? null : Date.valueOf(entry.expiryDate()),
                            entry.scope(), entry.sourceName(), entry.sourceUrl(), entry.articleNo(),
                            entry.content(), entry.keywords());
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("无法加载脱敏演示法规集", ex);
        }
    }

    public List<RegulationMatch> search(String query, String scope, LocalDate asOf, int requestedTopK) {
        if (query == null || query.isBlank()) {
            throw new BizException("法规检索关键词不能为空");
        }
        LocalDate effectiveAt = asOf == null ? LocalDate.now() : asOf;
        int topK = Math.max(1, Math.min(requestedTopK <= 0 ? 3 : requestedTopK, 10));
        String normalizedScope = scope == null || scope.isBlank() ? null : scope.toUpperCase(Locale.ROOT);

        List<RegulationEntry> candidates = jdbc.query("""
                        SELECT code, title, version_label, effective_date, expiry_date, scope, source_name,
                               source_url, article_no, content, keywords, demo_data
                        FROM regulation_entry
                        WHERE active = TRUE
                          AND effective_date <= ?
                          AND (expiry_date IS NULL OR expiry_date >= ?)
                          AND (? IS NULL OR scope = ? OR scope = 'GENERAL')
                        """,
                (rs, row) -> new RegulationEntry(
                        rs.getString("code"), rs.getString("title"), rs.getString("version_label"),
                        rs.getDate("effective_date").toLocalDate(),
                        rs.getDate("expiry_date") == null ? null : rs.getDate("expiry_date").toLocalDate(),
                        rs.getString("scope"), rs.getString("source_name"), rs.getString("source_url"),
                        rs.getString("article_no"), rs.getString("content"), rs.getString("keywords"),
                        rs.getBoolean("demo_data")),
                Date.valueOf(effectiveAt), Date.valueOf(effectiveAt), normalizedScope, normalizedScope);

        Set<String> terms = expandTerms(query);
        List<RegulationMatch> matches = new ArrayList<>();
        for (RegulationEntry entry : candidates) {
            double score = score(entry, terms, normalizedScope);
            if (score > 0) {
                matches.add(new RegulationMatch(entry, Math.min(1d, score / 10d), excerpt(entry.content(), terms)));
            }
        }
        return matches.stream()
                .sorted(Comparator.comparingDouble(RegulationMatch::relevanceScore).reversed()
                        .thenComparing(match -> match.entry().code()))
                .limit(topK)
                .toList();
    }

    public List<RegulationEntry> list() {
        return jdbc.query("""
                        SELECT code, title, version_label, effective_date, expiry_date, scope, source_name,
                               source_url, article_no, content, keywords, demo_data
                        FROM regulation_entry WHERE active = TRUE ORDER BY scope, code
                        """,
                (rs, row) -> new RegulationEntry(
                        rs.getString("code"), rs.getString("title"), rs.getString("version_label"),
                        rs.getDate("effective_date").toLocalDate(),
                        rs.getDate("expiry_date") == null ? null : rs.getDate("expiry_date").toLocalDate(),
                        rs.getString("scope"), rs.getString("source_name"), rs.getString("source_url"),
                        rs.getString("article_no"), rs.getString("content"), rs.getString("keywords"),
                        rs.getBoolean("demo_data")));
    }

    private static double score(RegulationEntry entry, Set<String> terms, String scope) {
        String title = entry.title().toLowerCase(Locale.ROOT);
        String keywords = entry.keywords().toLowerCase(Locale.ROOT);
        String content = entry.content().toLowerCase(Locale.ROOT);
        double score = 0d;
        for (String term : terms) {
            String lower = term.toLowerCase(Locale.ROOT);
            if (title.contains(lower)) {
                score += 3d;
            }
            if (keywords.contains(lower)) {
                score += 2d;
            }
            if (content.contains(lower)) {
                score += 1d;
            }
        }
        return score > 0 && scope != null && scope.equals(entry.scope()) ? score + 1.5d : score;
    }

    private static Set<String> expandTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        for (String term : query.strip().split("[\\s,，;；、/]+")) {
            if (!term.isBlank()) {
                terms.add(term);
            }
        }
        String lower = query.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "身份证", "证件", "个人信息", "privacy", "pii")) {
            terms.addAll(List.of("个人信息", "身份证", "最小必要"));
        }
        if (containsAny(lower, "争议", "仲裁", "诉讼", "管辖")) {
            terms.addAll(List.of("争议解决", "仲裁", "诉讼", "管辖"));
        }
        if (containsAny(lower, "责任", "免责", "追索")) {
            terms.addAll(List.of("责任", "免责", "无限责任", "放弃追索"));
        }
        if (containsAny(lower, "续期", "续约", "延长")) {
            terms.addAll(List.of("自动续期", "续约", "通知期限"));
        }
        if (containsAny(lower, "采购", "审批", "利益冲突")) {
            terms.addAll(List.of("采购", "审批", "利益冲突"));
        }
        return terms;
    }

    private static boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static String excerpt(String content, Set<String> terms) {
        int start = 0;
        for (String term : terms) {
            int index = content.indexOf(term);
            if (index >= 0) {
                start = Math.max(0, index - 30);
                break;
            }
        }
        int end = Math.min(content.length(), start + 180);
        return content.substring(start, end);
    }

    public record RegulationEntry(
            String code,
            String title,
            String versionLabel,
            LocalDate effectiveDate,
            LocalDate expiryDate,
            String scope,
            String sourceName,
            String sourceUrl,
            String articleNo,
            String content,
            String keywords,
            boolean demoData) {
    }

    public record RegulationMatch(RegulationEntry entry, double relevanceScore, String excerpt) {
    }

    private record SeedEntry(
            String code,
            String title,
            String versionLabel,
            LocalDate effectiveDate,
            LocalDate expiryDate,
            String scope,
            String sourceName,
            String sourceUrl,
            String articleNo,
            String content,
            String keywords) {
    }
}
