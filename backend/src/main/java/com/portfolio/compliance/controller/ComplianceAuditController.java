package com.portfolio.compliance.controller;

import com.portfolio.compliance.service.ComplianceAuditStreamService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/compliance")
public class ComplianceAuditController {

    private final ComplianceAuditStreamService streamService;

    public ComplianceAuditController(ComplianceAuditStreamService streamService) {
        this.streamService = streamService;
    }

    /** SSE 流式合规审核：start → finding* → token* → summary → done */
    @PostMapping(value = "/audit/stream/{docId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAudit(@PathVariable Long docId) {
        return streamService.streamAudit(docId);
    }
}
