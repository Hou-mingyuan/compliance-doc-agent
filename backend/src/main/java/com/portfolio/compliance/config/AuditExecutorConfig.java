package com.portfolio.compliance.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AuditExecutorConfig {

    @Bean(name = "auditExecutor", destroyMethod = "shutdown")
    ExecutorService auditExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        return Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "compliance-audit-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }
}
