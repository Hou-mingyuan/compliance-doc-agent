package com.portfolio.compliance.workflow;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.portfolio.compliance.common.BizException;
import com.portfolio.compliance.audit.AuditTrail;
import com.portfolio.compliance.common.Hashing;
import com.portfolio.compliance.entity.ComplianceDocument;
import com.portfolio.compliance.knowledge.RegulationCatalog.RegulationMatch;
import com.portfolio.compliance.security.ActorContext;
import com.portfolio.compliance.workflow.DocumentLocator.Location;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ReviewStore {

    private final JdbcTemplate jdbc;
    private final AuditTrail audit;

    public ReviewStore(JdbcTemplate jdbc, AuditTrail audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public ReviewRecord createReview(
            ComplianceDocument document,
            ActorContext actor,
            String rulePackVersion,
            String llmProvider) {
        String key = "REV-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                        INSERT INTO review_run
                        (review_key, tenant_id, document_id, created_by, status, rule_pack_version,
                         llm_provider, risk_score, cancel_requested, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 0, FALSE, ?, ?)
                        """,
                key, document.getTenantId(), document.getId(), actor.userId(), ReviewStatus.CREATED.name(),
                rulePackVersion, llmProvider, now, now);
        return requireReview(key, actor);
    }

    public ReviewRecord requireReview(String reviewKey, ActorContext actor) {
        List<ReviewRecord> rows = jdbc.query("SELECT * FROM review_run WHERE review_key = ?", this::mapReview, reviewKey);
        if (rows.isEmpty()) {
            throw new BizException(404, "审核运行不存在");
        }
        ReviewRecord review = rows.get(0);
        actor.requireTenant(review.tenantId());
        audit.recordCrossTenantRead(actor, review.tenantId(), "review", review.reviewKey());
        return review;
    }

    public ReviewRecord requireReview(Long reviewId, ActorContext actor) {
        List<ReviewRecord> rows = jdbc.query("SELECT * FROM review_run WHERE id = ?", this::mapReview, reviewId);
        if (rows.isEmpty()) {
            throw new BizException(404, "审核运行不存在");
        }
        ReviewRecord review = rows.get(0);
        actor.requireTenant(review.tenantId());
        audit.recordCrossTenantRead(actor, review.tenantId(), "review", review.reviewKey());
        return review;
    }

    public List<ReviewRecord> listReviews(ActorContext actor) {
        if (actor.role() == com.portfolio.compliance.security.AppRole.SYSTEM_ADMIN) {
            List<ReviewRecord> rows = jdbc.query("SELECT * FROM review_run ORDER BY created_at DESC", this::mapReview);
            rows.forEach(review -> audit.recordCrossTenantRead(
                    actor, review.tenantId(), "review", review.reviewKey()));
            return rows;
        }
        return jdbc.query(
                "SELECT * FROM review_run WHERE tenant_id = ? ORDER BY created_at DESC",
                this::mapReview,
                actor.tenantId());
    }

    public ReviewRecord findActiveForDocument(Long documentId, String tenantId) {
        List<ReviewRecord> rows = jdbc.query("""
                        SELECT * FROM review_run
                        WHERE document_id = ? AND tenant_id = ? AND status IN ('CREATED', 'RUNNING')
                        ORDER BY created_at DESC LIMIT 1
                        """,
                this::mapReview,
                documentId,
                tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean transition(Long reviewId, ReviewStatus from, ReviewStatus to) {
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbc.update(
                "UPDATE review_run SET status = ?, updated_at = ?, started_at = COALESCE(started_at, ?) "
                        + "WHERE id = ? AND status = ?",
                to.name(), now, to == ReviewStatus.RUNNING ? now : null, reviewId, from.name());
        return updated == 1;
    }

    public boolean finish(
            Long reviewId,
            ReviewStatus expected,
            ReviewStatus status,
            int riskScore,
            String summary) {
        int updated = jdbc.update("""
                        UPDATE review_run
                        SET status = ?, risk_score = ?, summary = ?, finished_at = ?, updated_at = ?
                        WHERE id = ? AND status = ?
                        """,
                status.name(), riskScore, summary, LocalDateTime.now(), LocalDateTime.now(), reviewId,
                expected.name());
        return updated == 1;
    }

    public void requestCancel(Long reviewId) {
        jdbc.update("UPDATE review_run SET cancel_requested = TRUE, updated_at = ? WHERE id = ?",
                LocalDateTime.now(), reviewId);
    }

    public boolean isCancelRequested(Long reviewId) {
        Boolean cancelled = jdbc.queryForObject(
                "SELECT cancel_requested FROM review_run WHERE id = ?", Boolean.class, reviewId);
        return Boolean.TRUE.equals(cancelled);
    }

    public FindingRecord saveFinding(Long reviewId, ComplianceDocument doc, FindingDraft draft) {
        String findingKey = "FND-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        String dedupeKey = Hashing.sha256(String.join("|",
                nullSafe(draft.sourceType()), nullSafe(draft.ruleCode()), nullSafe(draft.title()),
                String.valueOf(draft.location().matchStart()), String.valueOf(draft.location().matchEnd())));
        LocalDateTime now = LocalDateTime.now();
        try {
            jdbc.update("""
                            INSERT INTO risk_finding
                            (finding_key, tenant_id, review_id, document_id, severity, title, description,
                             source_type, rule_code, evidence_text, suggestion, chunk_id, page_no,
                             section_title, paragraph_no, match_start, match_end, confidence, status,
                             dedupe_key, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    findingKey, doc.getTenantId(), reviewId, doc.getId(), draft.severity().name(),
                    draft.title(), draft.description(), draft.sourceType(), draft.ruleCode(), draft.evidenceText(),
                    draft.suggestion(), draft.location().chunkId(), draft.location().pageNo(),
                    draft.location().sectionTitle(), draft.location().paragraphNo(), draft.location().matchStart(),
                    draft.location().matchEnd(), draft.confidence(), FindingStatus.OPEN.name(), dedupeKey, now, now);
        } catch (DuplicateKeyException ignored) {
            // Repeated tool calls within one review return the already persisted finding.
        }
        return jdbc.queryForObject(
                "SELECT * FROM risk_finding WHERE review_id = ? AND dedupe_key = ?",
                this::mapFinding,
                reviewId,
                dedupeKey);
    }

    public List<FindingRecord> listFindings(Long reviewId) {
        return jdbc.query(
                "SELECT * FROM risk_finding WHERE review_id = ? ORDER BY "
                        + "CASE severity WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 "
                        + "WHEN 'LOW' THEN 4 ELSE 5 END, id",
                this::mapFinding,
                reviewId);
    }

    public FindingRecord requireFinding(String findingKey, ActorContext actor) {
        List<FindingRecord> rows = jdbc.query(
                "SELECT * FROM risk_finding WHERE finding_key = ?", this::mapFinding, findingKey);
        if (rows.isEmpty()) {
            throw new BizException(404, "风险项不存在");
        }
        FindingRecord finding = rows.get(0);
        actor.requireTenant(finding.tenantId());
        audit.recordCrossTenantRead(actor, finding.tenantId(), "finding", finding.findingKey());
        return finding;
    }

    public FindingRecord reviewFinding(
            String findingKey,
            FindingStatus expected,
            FindingStatus target,
            String comment,
            ActorContext actor) {
        FindingRecord finding = requireFinding(findingKey, actor);
        int updated = jdbc.update("""
                        UPDATE risk_finding SET status = ?, reviewer_comment = ?, reviewed_by = ?,
                        reviewed_at = ?, updated_at = ? WHERE id = ? AND status = ?
                        """,
                target.name(), comment, actor.userId(), LocalDateTime.now(), LocalDateTime.now(),
                finding.id(), expected.name());
        if (updated != 1) {
            throw new BizException(409, "风险项状态已变化，请刷新后重试");
        }
        return requireFinding(findingKey, actor);
    }

    public void linkRegulation(Long findingId, RegulationMatch match) {
        try {
            jdbc.update("""
                            INSERT INTO finding_regulation
                            (finding_id, regulation_code, relevance_score, excerpt) VALUES (?, ?, ?, ?)
                            """,
                    findingId, match.entry().code(), match.relevanceScore(), match.excerpt());
        } catch (DuplicateKeyException ignored) {
            // Idempotent citation linking.
        }
    }

    public List<CitationRecord> listCitations(Long findingId) {
        return jdbc.query("""
                        SELECT fr.regulation_code, fr.relevance_score, fr.excerpt,
                               r.title, r.version_label, r.effective_date, r.expiry_date, r.scope,
                               r.source_name, r.source_url, r.article_no, r.demo_data
                        FROM finding_regulation fr
                        JOIN regulation_entry r ON r.code = fr.regulation_code
                        WHERE fr.finding_id = ? ORDER BY fr.relevance_score DESC, fr.regulation_code
                        """,
                (rs, row) -> new CitationRecord(
                        rs.getString("regulation_code"), rs.getDouble("relevance_score"), rs.getString("excerpt"),
                        rs.getString("title"), rs.getString("version_label"),
                        rs.getDate("effective_date").toLocalDate(),
                        rs.getDate("expiry_date") == null ? null : rs.getDate("expiry_date").toLocalDate(),
                        rs.getString("scope"), rs.getString("source_name"), rs.getString("source_url"),
                        rs.getString("article_no"), rs.getBoolean("demo_data")),
                findingId);
    }

    public EntityRecord saveEntity(
            Long reviewId,
            ComplianceDocument doc,
            String type,
            String value,
            String normalizedValue,
            int start,
            int end,
            double confidence,
            Location location) {
        String key = "ENT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        jdbc.update("""
                        INSERT INTO document_entity
                        (entity_key, tenant_id, review_id, document_id, entity_type, entity_value,
                         normalized_value, match_start, match_end, chunk_id, page_no, section_title,
                         paragraph_no, confidence)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                key, doc.getTenantId(), reviewId, doc.getId(), type, value, normalizedValue, start, end,
                location.chunkId(), location.pageNo(), location.sectionTitle(), location.paragraphNo(), confidence);
        return jdbc.queryForObject("SELECT * FROM document_entity WHERE entity_key = ?", this::mapEntity, key);
    }

    public List<EntityRecord> listEntities(Long reviewId) {
        return jdbc.query(
                "SELECT * FROM document_entity WHERE review_id = ? ORDER BY match_start, entity_type",
                this::mapEntity,
                reviewId);
    }

    public RiskSummary summarize(Long reviewId) {
        List<FindingRecord> findings = listFindings(reviewId).stream()
                .filter(f -> f.status() != FindingStatus.FALSE_POSITIVE)
                .toList();
        int score = Math.min(100, findings.stream().mapToInt(f -> f.severity().weight()).sum());
        RiskSeverity level = score >= 70 ? RiskSeverity.CRITICAL
                : score >= 45 ? RiskSeverity.HIGH
                : score >= 20 ? RiskSeverity.MEDIUM
                : score > 0 ? RiskSeverity.LOW : RiskSeverity.INFO;
        long confirmed = findings.stream().filter(f -> f.status() == FindingStatus.CONFIRMED
                || f.status() == FindingStatus.REMEDIATION_REQUIRED).count();
        return new RiskSummary(score, level, findings.size(), confirmed,
                "规则初筛命中 %d 项，初始综合分 %d，等级%s。人工复核结论另行记录。"
                        .formatted(findings.size(), score, riskLevelLabel(level)));
    }

    private String riskLevelLabel(RiskSeverity level) {
        return switch (level) {
            case CRITICAL -> "严重";
            case HIGH -> "高";
            case MEDIUM -> "中";
            case LOW -> "低";
            case INFO -> "提示";
        };
    }

    private ReviewRecord mapReview(ResultSet rs, int row) throws SQLException {
        return new ReviewRecord(
                rs.getLong("id"), rs.getString("review_key"), rs.getString("tenant_id"),
                rs.getLong("document_id"), rs.getString("created_by"), ReviewStatus.valueOf(rs.getString("status")),
                rs.getString("rule_pack_version"), rs.getString("llm_provider"), rs.getInt("risk_score"),
                rs.getString("summary"), rs.getBoolean("cancel_requested"),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private FindingRecord mapFinding(ResultSet rs, int row) throws SQLException {
        return new FindingRecord(
                rs.getLong("id"), rs.getString("finding_key"), rs.getString("tenant_id"),
                rs.getLong("review_id"), rs.getLong("document_id"), RiskSeverity.valueOf(rs.getString("severity")),
                rs.getString("title"), rs.getString("description"), rs.getString("source_type"),
                rs.getString("rule_code"), rs.getString("evidence_text"), rs.getString("suggestion"),
                nullableLong(rs, "chunk_id"), nullableInt(rs, "page_no"), rs.getString("section_title"),
                nullableInt(rs, "paragraph_no"), nullableInt(rs, "match_start"), nullableInt(rs, "match_end"),
                rs.getDouble("confidence"), FindingStatus.valueOf(rs.getString("status")),
                rs.getString("reviewer_comment"), rs.getString("reviewed_by"),
                rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private EntityRecord mapEntity(ResultSet rs, int row) throws SQLException {
        return new EntityRecord(
                rs.getLong("id"), rs.getString("entity_key"), rs.getString("tenant_id"),
                rs.getLong("review_id"), rs.getLong("document_id"), rs.getString("entity_type"),
                rs.getString("entity_value"), rs.getString("normalized_value"), rs.getInt("match_start"),
                rs.getInt("match_end"), nullableLong(rs, "chunk_id"), nullableInt(rs, "page_no"),
                rs.getString("section_title"), nullableInt(rs, "paragraph_no"), rs.getDouble("confidence"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String name) throws SQLException {
        int value = rs.getInt(name);
        return rs.wasNull() ? null : value;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    public record ReviewRecord(
            Long id,
            String reviewKey,
            String tenantId,
            Long documentId,
            String createdBy,
            ReviewStatus status,
            String rulePackVersion,
            String llmProvider,
            int riskScore,
            String summary,
            boolean cancelRequested,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    public record FindingDraft(
            RiskSeverity severity,
            String title,
            String description,
            String sourceType,
            String ruleCode,
            String evidenceText,
            String suggestion,
            Location location,
            double confidence) {
    }

    public record FindingRecord(
            Long id,
            String findingKey,
            String tenantId,
            Long reviewId,
            Long documentId,
            RiskSeverity severity,
            String title,
            String description,
            String sourceType,
            String ruleCode,
            String evidenceText,
            String suggestion,
            Long chunkId,
            Integer pageNo,
            String sectionTitle,
            Integer paragraphNo,
            Integer matchStart,
            Integer matchEnd,
            double confidence,
            FindingStatus status,
            String reviewerComment,
            String reviewedBy,
            LocalDateTime reviewedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    public record CitationRecord(
            String regulationCode,
            double relevanceScore,
            String excerpt,
            String title,
            String versionLabel,
            java.time.LocalDate effectiveDate,
            java.time.LocalDate expiryDate,
            String scope,
            String sourceName,
            String sourceUrl,
            String articleNo,
            boolean demoData) {
    }

    public record EntityRecord(
            Long id,
            String entityKey,
            String tenantId,
            Long reviewId,
            Long documentId,
            String type,
            String value,
            String normalizedValue,
            int start,
            int end,
            Long chunkId,
            Integer pageNo,
            String sectionTitle,
            Integer paragraphNo,
            double confidence,
            LocalDateTime createdAt) {
    }

    public record RiskSummary(
            int riskScore,
            RiskSeverity riskLevel,
            long findingCount,
            long confirmedCount,
            String summary) {
    }
}
