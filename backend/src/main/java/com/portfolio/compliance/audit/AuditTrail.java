package com.portfolio.compliance.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.compliance.common.Hashing;
import com.portfolio.compliance.security.ActorContext;
import com.portfolio.compliance.security.AppRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AuditTrail {

    private static final String GENESIS = "0".repeat(64);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditTrail(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public synchronized AuditEvent append(
            ActorContext actor,
            String action,
            String resourceType,
            String resourceId,
            String fromState,
            String toState,
            Map<String, Object> details) {
        String detailsJson = toJson(details == null ? Map.of() : details);
        String previousHash = latestHash(actor.tenantId());
        LocalDateTime createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        String eventKey = "EVT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        String eventHash = calculateHash(
                previousHash, eventKey, actor.tenantId(), actor.userId(), actor.role().name(), action,
                resourceType, resourceId, fromState, toState, detailsJson, createdAt);
        jdbc.update("""
                        INSERT INTO audit_event
                        (event_key, tenant_id, actor_id, actor_role, action, resource_type, resource_id,
                         from_state, to_state, details_json, previous_hash, event_hash, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                eventKey, actor.tenantId(), actor.userId(), actor.role().name(), action, resourceType,
                resourceId, fromState, toState, detailsJson, previousHash, eventHash, createdAt);
        return new AuditEvent(
                eventKey, actor.tenantId(), actor.userId(), actor.role().name(), action, resourceType,
                resourceId, fromState, toState, detailsJson, previousHash, eventHash, createdAt);
    }

    public List<AuditEvent> list(ActorContext actor, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit <= 0 ? 100 : requestedLimit, 500));
        if (actor.role() == com.portfolio.compliance.security.AppRole.SYSTEM_ADMIN) {
            return jdbc.query("SELECT * FROM audit_event ORDER BY id DESC LIMIT ?", this::map, limit);
        }
        return jdbc.query(
                "SELECT * FROM audit_event WHERE tenant_id = ? ORDER BY id DESC LIMIT ?",
                this::map,
                actor.tenantId(),
                limit);
    }

    public ChainVerification verify(String tenantId) {
        List<AuditEvent> events = jdbc.query(
                "SELECT * FROM audit_event WHERE tenant_id = ? ORDER BY id", this::map, tenantId);
        String expectedPrevious = GENESIS;
        for (AuditEvent event : events) {
            String expectedHash = calculateHash(
                    expectedPrevious, event.eventKey(), event.tenantId(), event.actorId(), event.actorRole(),
                    event.action(), event.resourceType(), event.resourceId(), event.fromState(), event.toState(),
                    event.detailsJson(), event.createdAt());
            if (!expectedPrevious.equals(event.previousHash()) || !expectedHash.equals(event.eventHash())) {
                return new ChainVerification(false, events.size(), event.eventKey(), expectedPrevious);
            }
            expectedPrevious = event.eventHash();
        }
        return new ChainVerification(true, events.size(), null, expectedPrevious);
    }

    public void recordCrossTenantRead(
            ActorContext actor,
            String resourceTenantId,
            String resourceType,
            String resourceId) {
        if (actor.role() == AppRole.SYSTEM_ADMIN && !actor.tenantId().equals(resourceTenantId)) {
            append(actor.forTenant(resourceTenantId), "SYSTEM_CROSS_TENANT_READ", resourceType, resourceId,
                    null, null, Map.of("originTenant", actor.tenantId()));
        }
    }

    private String latestHash(String tenantId) {
        List<String> hashes = jdbc.query(
                "SELECT event_hash FROM audit_event WHERE tenant_id = ? ORDER BY id DESC LIMIT 1",
                (rs, row) -> rs.getString(1),
                tenantId);
        return hashes.isEmpty() ? GENESIS : hashes.get(0);
    }

    private String calculateHash(
            String previousHash,
            String eventKey,
            String tenantId,
            String actorId,
            String actorRole,
            String action,
            String resourceType,
            String resourceId,
            String fromState,
            String toState,
            String detailsJson,
            LocalDateTime createdAt) {
        return Hashing.sha256(String.join("|",
                previousHash, eventKey, tenantId, actorId, actorRole, action, resourceType, resourceId,
                nullSafe(fromState), nullSafe(toState), detailsJson, createdAt.toString()));
    }

    private AuditEvent map(ResultSet rs, int row) throws SQLException {
        return new AuditEvent(
                rs.getString("event_key"), rs.getString("tenant_id"), rs.getString("actor_id"),
                rs.getString("actor_role"), rs.getString("action"), rs.getString("resource_type"),
                rs.getString("resource_id"), rs.getString("from_state"), rs.getString("to_state"),
                rs.getString("details_json"), rs.getString("previous_hash"), rs.getString("event_hash"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    public record AuditEvent(
            String eventKey,
            String tenantId,
            String actorId,
            String actorRole,
            String action,
            String resourceType,
            String resourceId,
            String fromState,
            String toState,
            String detailsJson,
            String previousHash,
            String eventHash,
            LocalDateTime createdAt) {
    }

    public record ChainVerification(boolean valid, int eventCount, String brokenAt, String latestHash) {
    }
}
