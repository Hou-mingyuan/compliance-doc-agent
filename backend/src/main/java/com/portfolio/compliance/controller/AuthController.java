package com.portfolio.compliance.controller;

import java.util.Map;
import java.util.List;

import com.portfolio.compliance.common.ApiResponse;
import com.portfolio.compliance.config.AppProperties;
import com.portfolio.compliance.security.ActorContext;
import com.portfolio.compliance.security.ActorContextProvider;
import com.portfolio.compliance.security.DemoIdentityDirectory;
import com.portfolio.compliance.security.DemoIdentityDirectory.AccountSummary;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final ActorContextProvider actors;
    private final AppProperties props;
    private final DemoIdentityDirectory identities;

    public AuthController(
            ActorContextProvider actors,
            AppProperties props,
            DemoIdentityDirectory identities) {
        this.actors = actors;
        this.props = props;
        this.identities = identities;
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        ActorContext actor = actors.current();
        return ApiResponse.ok(Map.of(
                "userId", actor.userId(),
                "tenantId", actor.tenantId(),
                "role", actor.role().name(),
                "demoMode", props.getSecurity().isDemoEnabled(),
                "disclaimer", "工程演示结果仅供人工复核，不替代律师意见或法定认证。"));
    }

    @GetMapping("/assignees")
    @PreAuthorize("hasAnyRole('COMPLIANCE_ADMIN','SYSTEM_ADMIN')")
    public ApiResponse<List<AccountSummary>> assignees(@RequestParam(required = false) String tenantId) {
        ActorContext actor = actors.current();
        return ApiResponse.ok(identities.assignees(actor, tenantId));
    }
}
