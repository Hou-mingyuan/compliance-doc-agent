package com.portfolio.compliance.security;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class DemoPrincipal implements UserDetails {

    private final String username;
    private final String password;
    private final String tenantId;
    private final AppRole role;

    public DemoPrincipal(String username, String password, String tenantId, AppRole role) {
        this.username = username;
        this.password = password;
        this.tenantId = tenantId;
        this.role = role;
    }

    public String tenantId() {
        return tenantId;
    }

    public AppRole role() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }
}
