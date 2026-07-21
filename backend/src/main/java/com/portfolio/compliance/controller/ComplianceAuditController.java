package com.portfolio.compliance.controller;

import com.portfolio.compliance.service.ComplianceAuditStreamService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api")
public class ComplianceAuditController {

    private final ComplianceAuditStreamService streamService;

    public ComplianceAuditController(ComplianceAuditStreamService streamService) {
        this.streamService = streamService;
    }

    /** SSE 流式合规审核：start → finding* → token* → summary → done */
    @PostMapping(value = "/compliance/audit/stream/{docId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAnyRole('USER','REVIEWER','COMPLIANCE_ADMIN','SYSTEM_ADMIN')")
    public SseEmitter streamAudit(@PathVariable Long docId) {
        return streamService.streamAudit(docId);
    }

    @PostMapping(value = "/reviews/stream/{docId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("hasAnyRole('USER','REVIEWER','COMPLIANCE_ADMIN','SYSTEM_ADMIN')")
    public SseEmitter streamReview(@PathVariable Long docId) {
        return streamService.streamAudit(docId);
    }
}
