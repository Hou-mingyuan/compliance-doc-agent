package com.portfolio.compliance.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.compliance.agent.ComplianceAgentOrchestrator;
import com.portfolio.compliance.common.BizException;
import com.portfolio.compliance.entity.ComplianceCheck;
import com.portfolio.compliance.entity.ComplianceDocument;
import com.portfolio.compliance.mapper.ComplianceCheckMapper;
import com.portfolio.compliance.mapper.ComplianceDocumentMapper;
import com.portfolio.compliance.rules.ComplianceRule;
import com.portfolio.compliance.rules.RuleSeverity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class ComplianceAuditStreamService {

    private static final Logger log = LoggerFactory.getLogger(ComplianceAuditStreamService.class);

    private final ComplianceDocumentMapper documentMapper;
    private final ComplianceCheckMapper checkMapper;
    private final ComplianceAgentOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    public ComplianceAuditStreamService(
            ComplianceDocumentMapper documentMapper,
            ComplianceCheckMapper checkMapper,
            ComplianceAgentOrchestrator orchestrator,
            ObjectMapper objectMapper) {
        this.documentMapper = documentMapper;
        this.checkMapper = checkMapper;
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
    }

    public SseEmitter streamAudit(Long docId) {
        SseEmitter emitter = new SseEmitter(120_000L);
        CompletableFuture.runAsync(() -> runStream(docId, emitter));
        return emitter;
    }

    private void runStream(Long docId, SseEmitter emitter) {
        String auditId = UUID.randomUUID().toString();
        try {
            ComplianceDocument doc = documentMapper.selectById(docId);
            if (doc == null) {
                sendEvent(emitter, "error", Map.of("message", "文档不存在：" + docId));
                emitter.complete();
                return;
            }
            if (doc.getContent() == null || doc.getContent().isBlank()) {
                sendEvent(emitter, "error", Map.of("message", "文档内容为空，无法审核"));
                emitter.complete();
                return;
            }

            doc.setStatus("AUDITING");
            doc.setUpdatedAt(LocalDateTime.now());
            documentMapper.updateById(doc);

            sendEvent(emitter, "start", Map.of(
                    "auditId", auditId,
                    "documentId", String.valueOf(docId)));

            List<ComplianceRule> ruleHits = new java.util.ArrayList<>();

            String narrative = orchestrator.analyzeStream(doc.getTitle(), doc.getContent(), new com.portfolio.compliance.agent.AuditStreamCallbacks() {
                @Override
                public void onFinding(ComplianceRule rule) {
                    ruleHits.add(rule);
                    sendEvent(emitter, "finding", toFindingPayload(rule));
                }

                @Override
                public void onToken(String token) {
                    sendEvent(emitter, "narrative", Map.of("text", token));
                }
            });

            persistRuleHits(docId, ruleHits);

            String summary = buildSummary(ruleHits, narrative);
            sendEvent(emitter, "summary", Map.of("text", summary));
            sendEvent(emitter, "done", Map.of(
                    "auditId", auditId,
                    "summary", summary));

            doc.setStatus("DONE");
            doc.setUpdatedAt(LocalDateTime.now());
            documentMapper.updateById(doc);

            emitter.complete();
        } catch (Exception e) {
            log.error("SSE audit failed docId={}", docId, e);
            try {
                sendEvent(emitter, "error", Map.of("message", e.getMessage() == null ? "审核失败" : e.getMessage()));
            } catch (Exception ignored) {
                /* emitter may already be closed */
            }
            emitter.completeWithError(e);
        }
    }

    private Map<String, Object> toFindingPayload(ComplianceRule rule) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("severity", mapSeverity(rule.severity()));
        map.put("rule", rule.name());
        map.put("description", rule.message());
        map.put("location", rule.code());
        return map;
    }

    private String mapSeverity(RuleSeverity severity) {
        return switch (severity) {
            case ERROR -> "high";
            case WARNING -> "medium";
            case INFO -> "info";
        };
    }

    private String buildSummary(List<ComplianceRule> rules, String narrative) {
        if (rules.isEmpty()) {
            return "规则引擎未命中风险项。" + (narrative.isBlank() ? "" : " " + firstLine(narrative));
        }
        RuleSeverity max = RuleSeverity.INFO;
        for (ComplianceRule r : rules) {
            if (r.severity().ordinal() > max.ordinal()) {
                max = r.severity();
            }
        }
        return "共发现 %d 项风险（最高等级：%s）。".formatted(rules.size(), max.name());
    }

    private String firstLine(String text) {
        int idx = text.indexOf('\n');
        String line = idx >= 0 ? text.substring(0, idx) : text;
        return line.length() > 120 ? line.substring(0, 120) + "…" : line;
    }

    private void persistRuleHits(Long documentId, List<ComplianceRule> rules) {
        LocalDateTime now = LocalDateTime.now();
        for (ComplianceRule rule : rules) {
            ComplianceCheck check = new ComplianceCheck();
            check.setDocumentId(documentId);
            check.setRuleCode(rule.code());
            check.setSeverity(rule.severity().name());
            check.setMessage(rule.message());
            check.setCreatedAt(now);
            checkMapper.insert(check);
        }
    }

    private void sendEvent(SseEmitter emitter, String event, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(data)));
        } catch (Exception e) {
            throw new BizException(500, "SSE 推送失败：" + e.getMessage());
        }
    }
}
