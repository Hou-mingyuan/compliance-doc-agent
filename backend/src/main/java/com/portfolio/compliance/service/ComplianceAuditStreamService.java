package com.portfolio.compliance.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.compliance.agent.AuditStreamCallbacks;
import com.portfolio.compliance.agent.ComplianceAgentOrchestrator;
import com.portfolio.compliance.agent.ComplianceAgentOrchestrator.ReviewCancelledException;
import com.portfolio.compliance.agent.tool.ToolResult;
import com.portfolio.compliance.common.BizException;
import com.portfolio.compliance.security.ActorContext;
import com.portfolio.compliance.security.ActorContextProvider;
import com.portfolio.compliance.workflow.ReviewStore.FindingRecord;
import com.portfolio.compliance.workflow.ReviewStore;
import com.portfolio.compliance.workflow.ReviewWorkflowService;
import com.portfolio.compliance.workflow.ReviewWorkflowService.StartedReview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ComplianceAuditStreamService {

    private static final Logger log = LoggerFactory.getLogger(ComplianceAuditStreamService.class);

    private final ComplianceAgentOrchestrator orchestrator;
    private final ReviewWorkflowService workflow;
    private final ReviewStore reviews;
    private final ActorContextProvider actors;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public ComplianceAuditStreamService(
            ComplianceAgentOrchestrator orchestrator,
            ReviewWorkflowService workflow,
            ReviewStore reviews,
            ActorContextProvider actors,
            ObjectMapper objectMapper,
            @Qualifier("auditExecutor") ExecutorService executor) {
        this.orchestrator = orchestrator;
        this.workflow = workflow;
        this.reviews = reviews;
        this.actors = actors;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    public SseEmitter streamAudit(Long docId) {
        ActorContext actor = actors.current();
        StartedReview started = workflow.begin(docId, actor);
        ActorContext executionActor = started.executionActor();
        String requestId = org.slf4j.MDC.get("requestId");
        SseEmitter emitter = new SseEmitter(120_000L);
        emitter.onTimeout(() -> requestCancelQuietly(started, executionActor));
        emitter.onError(error -> requestCancelQuietly(started, executionActor));
        executor.submit(() -> runStream(started, executionActor, requestId, emitter));
        return emitter;
    }

    private void runStream(StartedReview started, ActorContext actor, String requestId, SseEmitter emitter) {
        try {
            sendEvent(emitter, "start", Map.of(
                    "reviewId", started.review().reviewKey(),
                    "auditId", started.review().reviewKey(),
                    "documentId", String.valueOf(started.document().getId())));
            var outcome = orchestrator.runReview(started.review(), started.document(), actor, new AuditStreamCallbacks() {
                @Override
                public void onFinding(FindingRecord finding) {
                    sendEvent(emitter, "finding", findingPayload(finding));
                }

                @Override
                public void onToken(String token) {
                    sendEvent(emitter, "narrative", Map.of("text", token));
                }

                @Override
                public void onTool(String toolName, ToolResult result) {
                    sendEvent(emitter, "tool", Map.of(
                            "name", toolName,
                            "ok", result.ok(),
                            "code", result.code(),
                            "summary", result.summary()));
                }

                @Override
                public void onStage(String stage, String message) {
                    sendEvent(emitter, "stage", Map.of("stage", stage, "message", message));
                }

                @Override
                public boolean isCancelled() {
                    return reviews.isCancelRequested(started.review().id());
                }
            });
            workflow.complete(started.review(), started.document(), outcome.riskSummary().riskScore(),
                    outcome.riskSummary().summary(), actor);
            sendEvent(emitter, "summary", Map.of(
                    "text", outcome.riskSummary().summary(),
                    "riskScore", outcome.riskSummary().riskScore(),
                    "riskLevel", outcome.riskSummary().riskLevel().name()));
            sendEvent(emitter, "done", Map.of(
                    "reviewId", started.review().reviewKey(),
                    "auditId", started.review().reviewKey(),
                    "summary", outcome.riskSummary().summary()));
            emitter.complete();
        } catch (ReviewCancelledException ex) {
            workflow.markCancelled(started.review(), started.document(), actor);
            sendEventQuietly(emitter, "cancelled", Map.of("reviewId", started.review().reviewKey()));
            emitter.complete();
        } catch (Exception ex) {
            log.error("SSE audit failed reviewKey={} requestId={}", started.review().reviewKey(), requestId, ex);
            workflow.fail(started.review(), started.document(), actor, ex instanceof BizException ? "BUSINESS" : "INTERNAL");
            sendEventQuietly(emitter, "error", Map.of(
                    "message", "审核失败，请重试",
                    "requestId", requestId == null ? "n/a" : requestId));
            emitter.complete();
        }
    }

    private Map<String, Object> findingPayload(FindingRecord finding) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", finding.findingKey());
        data.put("severity", finding.severity().name().toLowerCase());
        data.put("rule", finding.title());
        data.put("description", finding.description());
        data.put("location", finding.ruleCode() == null ? finding.sourceType() : finding.ruleCode());
        data.put("kind", finding.matchStart() == null ? "missing" : "hit");
        data.put("matchedText", finding.evidenceText());
        data.put("matchStart", finding.matchStart());
        data.put("matchEnd", finding.matchEnd());
        data.put("pageNo", finding.pageNo());
        data.put("sectionTitle", finding.sectionTitle());
        data.put("paragraphNo", finding.paragraphNo());
        data.put("status", finding.status().name());
        data.put("suggestion", finding.suggestion());
        return data;
    }

    private void requestCancelQuietly(StartedReview started, ActorContext actor) {
        try {
            workflow.requestCancel(started.review().reviewKey(), actor);
        } catch (Exception ignored) {
            // Stream may already have reached a terminal state.
        }
    }

    private void sendEvent(SseEmitter emitter, String event, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(data)));
        } catch (Exception ex) {
            throw new ReviewCancelledException();
        }
    }

    private void sendEventQuietly(SseEmitter emitter, String event, Map<String, Object> data) {
        try {
            sendEvent(emitter, event, data);
        } catch (Exception ignored) {
            // Client already disconnected.
        }
    }
}
