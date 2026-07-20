package com.portfolio.compliance.security;

import com.portfolio.compliance.common.BizException;

public record ActorContext(String userId, String tenantId, AppRole role) {

    public boolean canAccessTenant(String resourceTenantId) {
        return role == AppRole.SYSTEM_ADMIN || tenantId.equals(resourceTenantId);
    }

    public void requireTenant(String resourceTenantId) {
        if (!canAccessTenant(resourceTenantId)) {
            throw new BizException(403, "无权访问其他租户资源");
        }
    }

    public void requireRole(AppRole required) {
        if (!role.atLeast(required)) {
            throw new BizException(403, "当前角色无权执行该操作");
        }
    }

    public ActorContext forTenant(String resourceTenantId) {
        requireTenant(resourceTenantId);
        return tenantId.equals(resourceTenantId)
                ? this
                : new ActorContext(userId, resourceTenantId, role);
    }
}
