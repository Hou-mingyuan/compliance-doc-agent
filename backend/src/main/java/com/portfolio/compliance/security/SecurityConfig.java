package com.portfolio.compliance.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.compliance.common.ApiResponse;
import com.portfolio.compliance.config.AppProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder(AppProperties props) {
        int strength = props.getSecurity().getDemoBcryptStrength();
        if (strength < 8 || strength > 16) {
            throw new IllegalArgumentException("app.security.demo-bcrypt-strength 必须在 8 到 16 之间");
        }
        return new BCryptPasswordEncoder(strength);
    }

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilterRegistration(RequestIdFilter filter) {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    DemoIdentityDirectory userDetailsService(AppProperties props, PasswordEncoder encoder) {
        List<DemoPrincipal> users = new ArrayList<>();
        if (props.getSecurity().isDemoEnabled()) {
            String demo = encoder.encode(props.getSecurity().getDemoPassword());
            users.add(new DemoPrincipal("user@demo.local", demo, "tenant-a", AppRole.USER));
            users.add(new DemoPrincipal("operator@demo.local", demo, "tenant-a", AppRole.USER));
            users.add(new DemoPrincipal("reviewer@demo.local", demo, "tenant-a", AppRole.REVIEWER));
            users.add(new DemoPrincipal("compliance@demo.local", demo, "tenant-a", AppRole.COMPLIANCE_ADMIN));
            users.add(new DemoPrincipal("tenant-b@demo.local", demo, "tenant-b", AppRole.USER));
            users.add(new DemoPrincipal("reviewer-b@demo.local", demo, "tenant-b", AppRole.REVIEWER));
            users.add(new DemoPrincipal("compliance-b@demo.local", demo, "tenant-b", AppRole.COMPLIANCE_ADMIN));
            users.add(new DemoPrincipal(
                    "admin@demo.local",
                    encoder.encode(props.getSecurity().getAdminPassword()),
                    "system",
                    AppRole.SYSTEM_ADMIN));
        }
        return new DemoIdentityDirectory(users);
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RequestIdFilter requestIdFilter,
            ObjectMapper objectMapper) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/health").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(response, objectMapper, 401, "认证失败"))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(response, objectMapper, 403, "权限不足")))
                .headers(headers -> headers.frameOptions(frame -> frame.deny()))
                .addFilterBefore(requestIdFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void writeError(HttpServletResponse response, ObjectMapper om, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        om.writeValue(response.getWriter(), ApiResponse.error(status, message));
    }
}
