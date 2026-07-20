package com.portfolio.compliance.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.portfolio.compliance.common.BizException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/** Read-only local identity directory used by the zero-key demo profile. */
public final class DemoIdentityDirectory implements UserDetailsService {

    private final Map<String, DemoPrincipal> users;

    public DemoIdentityDirectory(List<DemoPrincipal> principals) {
        Map<String, DemoPrincipal> indexed = new LinkedHashMap<>();
        for (DemoPrincipal principal : principals) {
            indexed.put(normalize(principal.getUsername()), principal);
        }
        this.users = Map.copyOf(indexed);
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        DemoPrincipal principal = users.get(normalize(username));
        if (principal == null) {
            throw new UsernameNotFoundException("未知账户");
        }
        return principal;
    }

    public void requireTenantAssignee(String userId, String tenantId) {
        DemoPrincipal principal = users.get(normalize(userId));
        if (principal == null) {
            throw new BizException("整改负责人不是有效演示账户");
        }
        if (principal.role() == AppRole.SYSTEM_ADMIN || !principal.tenantId().equals(tenantId)) {
            throw new BizException(403, "整改负责人必须属于风险所在租户");
        }
    }

    public List<AccountSummary> assignees(ActorContext actor, String requestedTenantId) {
        String tenantId;
        if (actor.role() == AppRole.SYSTEM_ADMIN) {
            if (requestedTenantId == null || requestedTenantId.isBlank()) {
                throw new BizException("系统管理员必须指定 tenantId");
            }
            tenantId = requestedTenantId.strip();
        } else {
            tenantId = actor.tenantId();
        }
        return users.values().stream()
                .filter(user -> user.role() != AppRole.SYSTEM_ADMIN && user.tenantId().equals(tenantId))
                .map(user -> new AccountSummary(user.getUsername(), user.tenantId(), user.role()))
                .toList();
    }

    private static String normalize(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }

    public record AccountSummary(String userId, String tenantId, AppRole role) {
    }
}
