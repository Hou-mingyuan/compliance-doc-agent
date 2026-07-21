package com.portfolio.compliance.agent.tool;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.compliance.common.Hashing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ToolExecutionRecorder {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ToolExecutionRecorder(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void record(
            ToolContext context,
            String toolName,
            Map<String, Object> args,
            ToolResult result,
            long durationMs) {
        String executionKey = "TOOL-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 16).toUpperCase();
        String digest = Hashing.sha256(canonical(args));
        String summary = result.summary() == null ? "" : result.summary();
        if (summary.length() > 1000) {
            summary = summary.substring(0, 1000);
        }
        jdbc.update("""
                        INSERT INTO tool_execution
                        (execution_key, tenant_id, review_id, tool_name, success, error_code, args_digest,
                         summary, duration_ms, actor_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                executionKey, context.actor().tenantId(), context.reviewId(), toolName, result.ok(),
                result.ok() ? null : result.code(), digest, summary, durationMs, context.actor().userId(),
                LocalDateTime.now());
    }

    private String canonical(Map<String, Object> args) {
        try {
            return objectMapper.writeValueAsString(new java.util.TreeMap<>(args == null ? Map.of() : args));
        } catch (Exception ex) {
            return "{}";
        }
    }

    public List<ToolExecutionRecord> list(Long reviewId) {
        return jdbc.query("""
                        SELECT execution_key, tool_name, success, error_code, args_digest, summary,
                               duration_ms, actor_id, created_at
                        FROM tool_execution WHERE review_id = ? ORDER BY id
                        """,
                (rs, row) -> new ToolExecutionRecord(
                        rs.getString("execution_key"), rs.getString("tool_name"), rs.getBoolean("success"),
                        rs.getString("error_code"), rs.getString("args_digest"), rs.getString("summary"),
                        rs.getLong("duration_ms"), rs.getString("actor_id"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                reviewId);
    }

    public record ToolExecutionRecord(
            String executionKey,
            String toolName,
            boolean success,
            String errorCode,
            String argsDigest,
            String summary,
            long durationMs,
            String actorId,
            LocalDateTime createdAt) {
    }
}
