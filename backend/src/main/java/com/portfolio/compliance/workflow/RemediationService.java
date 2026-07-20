package com.portfolio.compliance.workflow;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.portfolio.compliance.audit.AuditTrail;
import com.portfolio.compliance.common.BizException;
import com.portfolio.compliance.security.ActorContext;
import com.portfolio.compliance.security.AppRole;
import com.portfolio.compliance.security.DemoIdentityDirectory;
import com.portfolio.compliance.workflow.ReviewStore.FindingRecord;
import com.portfolio.compliance.workflow.ReviewStore.ReviewRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RemediationService {

    private final JdbcTemplate jdbc;
    private final ReviewStore reviews;
    private final ReviewWorkflowService workflow;
    private final AuditTrail audit;
    private final DemoIdentityDirectory identities;

    public RemediationService(
            JdbcTemplate jdbc,
            ReviewStore reviews,
            ReviewWorkflowService workflow,
            AuditTrail audit,
            DemoIdentityDirectory identities) {
        this.jdbc = jdbc;
        this.reviews = reviews;
        this.workflow = workflow;
        this.audit = audit;
        this.identities = identities;
    }

    @Transactional
    public RemediationRecord create(
            ActorContext actor,
            String findingKey,
            String assigneeId,
            LocalDate dueDate,
            String description) {
        actor.requireRole(AppRole.COMPLIANCE_ADMIN);
        FindingRecord finding = reviews.requireFinding(findingKey, actor);
        ActorContext resourceActor = actor.forTenant(finding.tenantId());
        if (finding.status() != FindingStatus.CONFIRMED
                && finding.status() != FindingStatus.REMEDIATION_REQUIRED) {
            throw new BizException(409, "只有已确认风险可以创建整改任务");
        }
        if (assigneeId == null || assigneeId.isBlank()) {
            throw new BizException("整改负责人不能为空");
        }
        identities.requireTenantAssignee(assigneeId, finding.tenantId());
        if (dueDate == null || dueDate.isBefore(LocalDate.now())) {
            throw new BizException("整改截止日期不能早于今天");
        }
        List<RemediationRecord> existing = jdbc.query(
                "SELECT * FROM remediation_task WHERE finding_id = ?", this::mapTask, finding.id());
        if (!existing.isEmpty()) {
            return existing.get(0);
        }

        ReviewRecord review = reviews.requireReview(finding.reviewId(), actor);
        String key = "TASK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        LocalDateTime now = LocalDateTime.now();
        String safeDescription = requireText(description, "整改要求", 2000);
        jdbc.update("""
                        INSERT INTO remediation_task
                        (task_key, tenant_id, review_id, finding_id, assignee_id, due_date, status,
                         description, created_by, version_no, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                        """,
                key, finding.tenantId(), finding.reviewId(), finding.id(), assigneeId.strip(), dueDate,
                RemediationStatus.OPEN.name(), safeDescription, actor.userId(), now, now);
        jdbc.update("UPDATE risk_finding SET status = ?, updated_at = ? WHERE id = ?",
                FindingStatus.REMEDIATION_REQUIRED.name(), now, finding.id());
        if (review.status() == ReviewStatus.PENDING_REVIEW) {
            workflow.enterRemediation(review, resourceActor);
        }
        audit.append(resourceActor, "REMEDIATION_CREATED", "remediation", key, null, RemediationStatus.OPEN.name(),
                Map.of("findingKey", findingKey, "assigneeId", assigneeId, "dueDate", dueDate.toString()));
        return require(key, actor);
    }

    @Transactional
    public RemediationRecord start(String taskKey, ActorContext actor) {
        RemediationRecord task = require(taskKey, actor);
        requireAssigneeOrAdmin(task, actor);
        ActorContext resourceActor = actor.forTenant(task.tenantId());
        RemediationStatus from = switch (task.status()) {
            case OPEN, REOPENED, REJECTED -> task.status();
            default -> throw new BizException(409, "当前整改状态不能开始处理");
        };
        return transition(task, resourceActor, from, RemediationStatus.IN_PROGRESS, "REMEDIATION_STARTED", null);
    }

    @Transactional
    public RemediationRecord submitEvidence(String taskKey, String evidenceText, ActorContext actor) {
        RemediationRecord task = require(taskKey, actor);
        requireAssigneeOrAdmin(task, actor);
        ActorContext resourceActor = actor.forTenant(task.tenantId());
        String evidence = requireText(evidenceText, "整改证据", 4000);
        if (task.status() != RemediationStatus.IN_PROGRESS) {
            throw new BizException(409, "只有处理中任务可以提交证据");
        }
        jdbc.update("""
                        INSERT INTO remediation_evidence (task_id, submitted_by, evidence_text, submitted_at)
                        VALUES (?, ?, ?, ?)
                        """,
                task.id(), actor.userId(), evidence, LocalDateTime.now());
        return transition(task, resourceActor, RemediationStatus.IN_PROGRESS, RemediationStatus.EVIDENCE_SUBMITTED,
                "REMEDIATION_EVIDENCE_SUBMITTED", Map.of("evidenceLength", evidence.length()));
    }

    @Transactional
    public RemediationRecord reviewEvidence(
            String taskKey,
            boolean approved,
            String comment,
            ActorContext actor) {
        actor.requireRole(AppRole.REVIEWER);
        RemediationRecord task = require(taskKey, actor);
        ActorContext resourceActor = actor.forTenant(task.tenantId());
        if (task.status() != RemediationStatus.EVIDENCE_SUBMITTED) {
            throw new BizException(409, "只有已提交证据的任务可以复审");
        }
        String reviewComment = requireText(comment, "复审意见", 2000);
        RemediationStatus target = approved ? RemediationStatus.VERIFIED : RemediationStatus.REJECTED;
        jdbc.update("UPDATE remediation_task SET review_comment = ? WHERE id = ?", reviewComment, task.id());
        return transition(task, resourceActor, RemediationStatus.EVIDENCE_SUBMITTED, target,
                approved ? "REMEDIATION_VERIFIED" : "REMEDIATION_REJECTED",
                Map.of("comment", reviewComment));
    }

    @Transactional
    public RemediationRecord close(String taskKey, ActorContext actor) {
        actor.requireRole(AppRole.COMPLIANCE_ADMIN);
        RemediationRecord task = require(taskKey, actor);
        ActorContext resourceActor = actor.forTenant(task.tenantId());
        RemediationRecord closed = transition(
                task, resourceActor, RemediationStatus.VERIFIED, RemediationStatus.CLOSED, "REMEDIATION_CLOSED", null);
        jdbc.update("UPDATE remediation_task SET closed_at = ? WHERE id = ?", LocalDateTime.now(), task.id());
        jdbc.update("UPDATE risk_finding SET status = ?, updated_at = ? WHERE id = ?",
                FindingStatus.RESOLVED.name(), LocalDateTime.now(), task.findingId());
        ReviewRecord review = reviews.requireReview(task.reviewId(), actor);
        Integer open = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM remediation_task
                        WHERE review_id = ? AND status <> ?
                        """, Integer.class, task.reviewId(), RemediationStatus.CLOSED.name());
        if ((open == null || open == 0) && review.status() == ReviewStatus.REMEDIATION) {
            workflow.enterRecheck(review, resourceActor);
        }
        return require(taskKey, actor);
    }

    @Transactional
    public RemediationRecord reopen(String taskKey, String reason, ActorContext actor) {
        actor.requireRole(AppRole.COMPLIANCE_ADMIN);
        RemediationRecord task = require(taskKey, actor);
        ActorContext resourceActor = actor.forTenant(task.tenantId());
        String safeReason = requireText(reason, "重开原因", 1000);
        RemediationRecord reopened = transition(
                task, resourceActor, RemediationStatus.CLOSED, RemediationStatus.REOPENED, "REMEDIATION_REOPENED",
                Map.of("reason", safeReason));
        jdbc.update("UPDATE risk_finding SET status = ?, updated_at = ? WHERE id = ?",
                FindingStatus.REMEDIATION_REQUIRED.name(), LocalDateTime.now(), task.findingId());
        ReviewRecord review = reviews.requireReview(task.reviewId(), actor);
        if (review.status() == ReviewStatus.RECHECK || review.status() == ReviewStatus.APPROVED) {
            workflow.enterRemediation(review, resourceActor);
        }
        return reopened;
    }

    public List<RemediationRecord> list(ActorContext actor, Long reviewId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM remediation_task WHERE 1=1");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (actor.role() != AppRole.SYSTEM_ADMIN) {
            sql.append(" AND tenant_id = ?");
            args.add(actor.tenantId());
        }
        if (actor.role() == AppRole.USER) {
            sql.append(" AND assignee_id = ?");
            args.add(actor.userId());
        }
        if (reviewId != null) {
            ReviewRecord review = reviews.requireReview(reviewId, actor);
            sql.append(" AND review_id = ?");
            args.add(review.id());
        }
        sql.append(" ORDER BY updated_at DESC");
        List<RemediationRecord> tasks = jdbc.query(sql.toString(), this::mapTask, args.toArray());
        tasks.forEach(task -> audit.recordCrossTenantRead(
                actor, task.tenantId(), "remediation", task.taskKey()));
        return tasks;
    }

    public List<EvidenceRecord> listEvidence(Long taskId) {
        return jdbc.query("""
                        SELECT id, task_id, submitted_by, evidence_text, submitted_at
                        FROM remediation_evidence WHERE task_id = ? ORDER BY submitted_at
                        """,
                (rs, row) -> new EvidenceRecord(
                        rs.getLong("id"), rs.getLong("task_id"), rs.getString("submitted_by"),
                        rs.getString("evidence_text"), rs.getTimestamp("submitted_at").toLocalDateTime()),
                taskId);
    }

    public RemediationRecord require(String taskKey, ActorContext actor) {
        List<RemediationRecord> rows = jdbc.query(
                "SELECT * FROM remediation_task WHERE task_key = ?", this::mapTask, taskKey);
        if (rows.isEmpty()) {
            throw new BizException(404, "整改任务不存在");
        }
        RemediationRecord task = rows.get(0);
        actor.requireTenant(task.tenantId());
        audit.recordCrossTenantRead(actor, task.tenantId(), "remediation", task.taskKey());
        if (actor.role() == AppRole.USER && !task.assigneeId().equals(actor.userId())) {
            throw new BizException(403, "只能查看分配给自己的整改任务");
        }
        return task;
    }

    public RemediationDetail detail(String taskKey, ActorContext actor) {
        RemediationRecord task = require(taskKey, actor);
        return new RemediationDetail(task, listEvidence(task.id()));
    }

    private RemediationRecord transition(
            RemediationRecord task,
            ActorContext actor,
            RemediationStatus from,
            RemediationStatus to,
            String action,
            Map<String, Object> details) {
        int updated = jdbc.update("""
                        UPDATE remediation_task
                        SET status = ?, version_no = version_no + 1, updated_at = ?
                        WHERE id = ? AND status = ? AND version_no = ?
                        """,
                to.name(), LocalDateTime.now(), task.id(), from.name(), task.versionNo());
        if (updated != 1) {
            throw new BizException(409, "整改任务状态已变化，请刷新后重试");
        }
        audit.append(actor, action, "remediation", task.taskKey(), from.name(), to.name(),
                details == null ? Map.of() : details);
        return require(task.taskKey(), actor);
    }

    private void requireAssigneeOrAdmin(RemediationRecord task, ActorContext actor) {
        if (!task.assigneeId().equals(actor.userId()) && !actor.role().atLeast(AppRole.COMPLIANCE_ADMIN)) {
            throw new BizException(403, "只有整改负责人或合规管理员可以执行该操作");
        }
    }

    private RemediationRecord mapTask(ResultSet rs, int row) throws SQLException {
        return new RemediationRecord(
                rs.getLong("id"), rs.getString("task_key"), rs.getString("tenant_id"),
                rs.getLong("review_id"), rs.getLong("finding_id"), rs.getString("assignee_id"),
                rs.getDate("due_date").toLocalDate(), RemediationStatus.valueOf(rs.getString("status")),
                rs.getString("description"), rs.getString("review_comment"), rs.getString("created_by"),
                rs.getInt("version_no"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime(),
                rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toLocalDateTime());
    }

    private static String requireText(String text, String label, int max) {
        if (text == null || text.isBlank()) {
            throw new BizException(label + "不能为空");
        }
        String normalized = text.strip();
        if (normalized.length() > max) {
            throw new BizException(label + "不能超过 " + max + " 字");
        }
        return normalized;
    }

    public record RemediationRecord(
            Long id,
            String taskKey,
            String tenantId,
            Long reviewId,
            Long findingId,
            String assigneeId,
            LocalDate dueDate,
            RemediationStatus status,
            String description,
            String reviewComment,
            String createdBy,
            int versionNo,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            LocalDateTime closedAt) {
    }

    public record EvidenceRecord(
            Long id,
            Long taskId,
            String submittedBy,
            String evidenceText,
            LocalDateTime submittedAt) {
    }

    public record RemediationDetail(RemediationRecord task, List<EvidenceRecord> evidence) {
    }
}
