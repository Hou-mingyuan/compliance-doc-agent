package com.portfolio.compliance.agent.tool;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolExecutionConfig {

    @Bean(name = "toolExecutor", destroyMethod = "shutdown")
    ExecutorService toolExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        return Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "compliance-tool-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }
}
