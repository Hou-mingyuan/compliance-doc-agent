package com.portfolio.compliance.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.portfolio.compliance.llm.ToolSpec;
import com.portfolio.compliance.security.ActorContext;
import com.portfolio.compliance.security.AppRole;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    @Test
    void timeoutInterruptsSlowToolAndReturnsStructuredError() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AgentTool slow = new AgentTool() {
                @Override public String name() { return "slow_tool"; }
                @Override public ToolSpec spec() {
                    return new ToolSpec(name(), "slow", ToolSupport.schema(
                            Map.of("value", ToolSupport.prop("string", "test value")), List.of("value")));
                }
                @Override public AppRole minimumRole() { return AppRole.USER; }
                @Override public ToolResult execute(ToolContext context, Map<String, Object> args) {
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    return ToolResult.ok("late", Map.of());
                }
            };
            ToolRegistry registry = new ToolRegistry(List.of(slow), executor, null, 1);
            ToolContext context = new ToolContext(
                    new ActorContext("user", "tenant-a", AppRole.USER), 1L, 1L,
                    "GENERAL", "demo", "content");

            long started = System.nanoTime();
            ToolResult result = registry.execute("slow_tool", Map.of("value", "x"), context);
            long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(result.ok()).isFalse();
            assertThat(result.code()).isEqualTo("TIMEOUT");
            assertThat(elapsedMs).isBetween(900L, 2_500L);
        } finally {
            executor.shutdownNow();
        }
    }
}
