package com.portfolio.compliance.workflow;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.portfolio.compliance.audit.AuditTrail;
import com.portfolio.compliance.common.BizException;
import com.portfolio.compliance.entity.ComplianceDocument;
import com.portfolio.compliance.mapper.ComplianceDocumentMapper;
import com.portfolio.compliance.security.ActorContext;
import com.portfolio.compliance.security.AppRole;
import com.portfolio.compliance.service.DocumentUploadService;
import com.portfolio.compliance.workflow.ReviewStore.FindingRecord;
import com.portfolio.compliance.workflow.ReviewStore.ReviewRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewWorkflowService {

    private final ReviewStore reviews;
    private final DocumentUploadService documents;
    private final ComplianceDocumentMapper documentMapper;
    private final com.portfolio.compliance.rules.ComplianceRuleEngine rules;
    private final com.portfolio.compliance.llm.LlmClient llm;
    private final AuditTrail audit;

    public ReviewWorkflowService(
            ReviewStore reviews,
            DocumentUploadService documents,
            ComplianceDocumentMapper documentMapper,
            com.portfolio.compliance.rules.ComplianceRuleEngine rules,
            com.portfolio.compliance.llm.LlmClient llm,
            AuditTrail audit) {
        this.reviews = reviews;
        this.documents = documents;
        this.documentMapper = documentMapper;
        this.rules = rules;
        this.llm = llm;
        this.audit = audit;
    }

    @Transactional
    public StartedReview begin(Long documentId, ActorContext actor) {
        actor.requireRole(AppRole.USER);
        ComplianceDocument document = documents.requireDocument(documentId, actor);
        ActorContext executionActor = actor.forTenant(document.getTenantId());
        ReviewRecord active = reviews.findActiveForDocument(documentId, document.getTenantId());
        if (active != null) {
            throw new BizException(409, "该文档已有正在运行的审核：" + active.reviewKey());
        }
        ReviewRecord review = reviews.createReview(document, executionActor, rules.packVersion(), llm.provider());
        if (!reviews.transition(review.id(), ReviewStatus.CREATED, ReviewStatus.RUNNING)) {
            throw new BizException(409, "审核状态初始化冲突");
        }
        setDocumentStatus(document, "REVIEWING");
        audit.append(executionActor, "REVIEW_STARTED", "review", review.reviewKey(),
                ReviewStatus.CREATED.name(), ReviewStatus.RUNNING.name(),
                Map.of("documentId", documentId, "rulePackVersion", rules.packVersion(), "llmProvider", llm.provider()));
        return new StartedReview(reviews.requireReview(review.id(), actor), document, executionActor);
    }

    @Transactional
    public void complete(ReviewRecord review, ComplianceDocument document, int riskScore, String summary, ActorContext actor) {
        ActorContext resourceActor = actor.forTenant(review.tenantId());
        ReviewRecord current = reviews.requireReview(review.id(), actor);
        if (current.status() != ReviewStatus.RUNNING) {
            throw new BizException(409, "审核不在运行状态");
        }
        if (!reviews.finish(
                review.id(), ReviewStatus.RUNNING, ReviewStatus.PENDING_REVIEW, riskScore, summary)) {
            throw new BizException(409, "审核状态已变化，不能重复完成");
        }
        setDocumentStatus(document, "PENDING_REVIEW");
        audit.append(resourceActor, "REVIEW_COMPLETED", "review", review.reviewKey(),
                ReviewStatus.RUNNING.name(), ReviewStatus.PENDING_REVIEW.name(),
                Map.of("riskScore", riskScore, "findingCount", reviews.listFindings(review.id()).size()));
    }

    @Transactional
    public void fail(ReviewRecord review, ComplianceDocument document, ActorContext actor, String errorCode) {
        ActorContext resourceActor = actor.forTenant(review.tenantId());
        if (!reviews.finish(review.id(), ReviewStatus.RUNNING, ReviewStatus.FAILED, 0,
                "审核失败，请根据 request id 重试。")) {
            return;
        }
        setDocumentStatus(document, "FAILED");
        audit.append(resourceActor, "REVIEW_FAILED", "review", review.reviewKey(),
                ReviewStatus.RUNNING.name(), ReviewStatus.FAILED.name(), Map.of("errorCode", errorCode));
    }

    @Transactional
    public void markCancelled(ReviewRecord review, ComplianceDocument document, ActorContext actor) {
        ActorContext resourceActor = actor.forTenant(review.tenantId());
        if (!reviews.finish(review.id(), ReviewStatus.RUNNING, ReviewStatus.CANCELLED, 0,
                "审核已由用户取消。")) {
            return;
        }
        setDocumentStatus(document, "CANCELLED");
        audit.append(resourceActor, "REVIEW_CANCELLED", "review", review.reviewKey(),
                ReviewStatus.RUNNING.name(), ReviewStatus.CANCELLED.name(), Map.of());
    }

    @Transactional
    public ReviewRecord requestCancel(String reviewKey, ActorContext actor) {
        ReviewRecord review = reviews.requireReview(reviewKey, actor);
        ActorContext resourceActor = actor.forTenant(review.tenantId());
        if (review.status() == ReviewStatus.CANCELLED) {
            return review;
        }
        if (review.status() != ReviewStatus.RUNNING) {
            throw new BizException(409, "只有运行中的审核可以取消");
        }
        if (!review.createdBy().equals(actor.userId()) && !actor.role().atLeast(AppRole.REVIEWER)) {
            throw new BizException(403, "只能取消自己发起的审核");
        }
        reviews.requestCancel(review.id());
        audit.append(resourceActor, "REVIEW_CANCEL_REQUESTED", "review", review.reviewKey(),
                ReviewStatus.RUNNING.name(), ReviewStatus.RUNNING.name(), Map.of());
        return reviews.requireReview(review.id(), actor);
    }

    @Transactional
    public FindingRecord reviewFinding(
            String findingKey,
            boolean confirmed,
            String comment,
            ActorContext actor) {
        actor.requireRole(AppRole.REVIEWER);
        if (comment == null || comment.isBlank()) {
            throw new BizException("人工复核意见不能为空");
        }
        if (comment.strip().length() > 2000) {
            throw new BizException("人工复核意见不能超过 2000 字");
        }
        FindingRecord before = reviews.requireFinding(findingKey, actor);
        ActorContext resourceActor = actor.forTenant(before.tenantId());
        FindingStatus target = confirmed ? FindingStatus.CONFIRMED : FindingStatus.FALSE_POSITIVE;
        FindingRecord after = reviews.reviewFinding(
                findingKey, FindingStatus.OPEN, target, comment.strip(), actor);
        audit.append(resourceActor, confirmed ? "FINDING_CONFIRMED" : "FINDING_REJECTED", "finding", findingKey,
                before.status().name(), after.status().name(), Map.of("comment", comment.strip()));
        return after;
    }

    @Transactional
    public ReviewRecord approve(String reviewKey, String comment, ActorContext actor) {
        actor.requireRole(AppRole.REVIEWER);
        ReviewRecord review = reviews.requireReview(reviewKey, actor);
        ActorContext resourceActor = actor.forTenant(review.tenantId());
        if (review.status() != ReviewStatus.PENDING_REVIEW && review.status() != ReviewStatus.RECHECK) {
            throw new BizException(409, "当前审核状态不能批准");
        }
        List<FindingRecord> findings = reviews.listFindings(review.id());
        if (findings.stream().anyMatch(f -> f.status() == FindingStatus.OPEN
                || f.status() == FindingStatus.CONFIRMED
                || f.status() == FindingStatus.REMEDIATION_REQUIRED)) {
            throw new BizException(409, "仍有未复核或未闭环风险，不能批准");
        }
        if (comment == null || comment.isBlank()) {
            throw new BizException("批准意见不能为空");
        }
        if (!reviews.transition(review.id(), review.status(), ReviewStatus.APPROVED)) {
            throw new BizException(409, "审核状态已变化，请刷新后重试");
        }
        ComplianceDocument document = documents.requireDocument(review.documentId(), actor);
        setDocumentStatus(document, "APPROVED");
        audit.append(resourceActor, "REVIEW_APPROVED", "review", reviewKey, review.status().name(),
                ReviewStatus.APPROVED.name(), Map.of("comment", comment.strip()));
        return reviews.requireReview(review.id(), actor);
    }

    @Transactional
    public ReviewRecord enterRemediation(ReviewRecord review, ActorContext actor) {
        ActorContext resourceActor = actor.forTenant(review.tenantId());
        if (review.status() != ReviewStatus.PENDING_REVIEW
                && review.status() != ReviewStatus.RECHECK
                && review.status() != ReviewStatus.APPROVED) {
            throw new BizException(409, "当前审核状态不能进入整改");
        }
        if (!reviews.transition(review.id(), review.status(), ReviewStatus.REMEDIATION)) {
            throw new BizException(409, "审核状态已变化，请刷新后重试");
        }
        ComplianceDocument document = documents.requireDocument(review.documentId(), resourceActor);
        setDocumentStatus(document, "REMEDIATION");
        audit.append(resourceActor, "REVIEW_REMEDIATION_REQUIRED", "review", review.reviewKey(),
                review.status().name(), ReviewStatus.REMEDIATION.name(), Map.of());
        return reviews.requireReview(review.id(), resourceActor);
    }

    @Transactional
    public ReviewRecord enterRecheck(ReviewRecord review, ActorContext actor) {
        ActorContext resourceActor = actor.forTenant(review.tenantId());
        if (review.status() != ReviewStatus.REMEDIATION) {
            throw new BizException(409, "当前审核状态不能进入复审");
        }
        if (!reviews.transition(review.id(), ReviewStatus.REMEDIATION, ReviewStatus.RECHECK)) {
            throw new BizException(409, "审核状态已变化，请刷新后重试");
        }
        ComplianceDocument document = documents.requireDocument(review.documentId(), resourceActor);
        setDocumentStatus(document, "PENDING_REVIEW");
        audit.append(resourceActor, "REVIEW_RECHECK_READY", "review", review.reviewKey(),
                ReviewStatus.REMEDIATION.name(), ReviewStatus.RECHECK.name(), Map.of());
        return reviews.requireReview(review.id(), resourceActor);
    }

    private void setDocumentStatus(ComplianceDocument document, String status) {
        document.setStatus(status);
        document.setUpdatedAt(LocalDateTime.now());
        documentMapper.updateById(document);
    }

    public record StartedReview(ReviewRecord review, ComplianceDocument document, ActorContext executionActor) {
    }
}
