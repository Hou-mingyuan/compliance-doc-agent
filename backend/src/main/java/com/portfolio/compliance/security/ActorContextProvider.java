package com.portfolio.compliance.security;

import com.portfolio.compliance.common.BizException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class ActorContextProvider {

    public ActorContext current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof DemoPrincipal principal)) {
            throw new BizException(401, "请先登录");
        }
        return new ActorContext(principal.getUsername(), principal.tenantId(), principal.role());
    }
}
