package com.portfolio.compliance.controller;

import java.util.List;

import com.portfolio.compliance.audit.AuditTrail;
import com.portfolio.compliance.audit.AuditTrail.AuditEvent;
import com.portfolio.compliance.audit.AuditTrail.ChainVerification;
import com.portfolio.compliance.common.ApiResponse;
import com.portfolio.compliance.security.ActorContext;
import com.portfolio.compliance.security.ActorContextProvider;
import com.portfolio.compliance.security.AppRole;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
@PreAuthorize("hasAnyRole('COMPLIANCE_ADMIN','SYSTEM_ADMIN')")
public class AuditController {

    private final AuditTrail audit;
    private final ActorContextProvider actors;

    public AuditController(AuditTrail audit, ActorContextProvider actors) {
        this.audit = audit;
        this.actors = actors;
    }

    @GetMapping("/events")
    public ApiResponse<List<AuditEvent>> events(@RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(audit.list(actors.current(), limit));
    }

    @GetMapping("/verify")
    public ApiResponse<ChainVerification> verify(@RequestParam(required = false) String tenantId) {
        ActorContext actor = actors.current();
        String targetTenant;
        if (actor.role() == AppRole.SYSTEM_ADMIN) {
            if (tenantId == null || tenantId.isBlank()) {
                throw new com.portfolio.compliance.common.BizException("系统管理员必须指定 tenantId");
            }
            targetTenant = tenantId.strip();
        } else {
            targetTenant = actor.tenantId();
        }
        return ApiResponse.ok(audit.verify(targetTenant));
    }
}
