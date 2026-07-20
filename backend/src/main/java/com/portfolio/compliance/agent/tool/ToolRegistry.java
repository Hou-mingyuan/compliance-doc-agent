package com.portfolio.compliance.agent.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.portfolio.compliance.config.AppProperties;
import com.portfolio.compliance.llm.ToolSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final ExecutorService executor;
    private final ToolExecutionRecorder recorder;
    private final int timeoutSeconds;

    @Autowired
    public ToolRegistry(
            List<AgentTool> toolList,
            @Qualifier("toolExecutor") ExecutorService executor,
            ToolExecutionRecorder recorder,
            AppProperties props) {
        this(toolList, executor, recorder, Math.max(1, props.getAgent().getToolTimeoutSeconds()));
    }

    public ToolRegistry(List<AgentTool> toolList) {
        this(toolList, ForkJoinPool.commonPool(), null, 3);
    }

    ToolRegistry(
            List<AgentTool> toolList,
            ExecutorService executor,
            ToolExecutionRecorder recorder,
            int timeoutSeconds) {
        for (AgentTool tool : toolList) {
            if (tools.put(tool.name(), tool) != null) {
                throw new IllegalStateException("重复工具名：" + tool.name());
            }
        }
        this.executor = executor;
        this.recorder = recorder;
        this.timeoutSeconds = timeoutSeconds;
    }

    public List<ToolSpec> specs() {
        return tools.values().stream().map(AgentTool::spec).toList();
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    public ToolResult execute(String name, Map<String, Object> args, ToolContext context) {
        long started = System.nanoTime();
        if (context == null || context.actor() == null || context.documentId() == null) {
            return ToolResult.fail("CONTEXT_REQUIRED", "工具执行缺少受信任的文档与调用者上下文");
        }
        AgentTool tool = tools.get(name);
        ToolResult result;
        if (tool == null) {
            result = ToolResult.fail("UNKNOWN_TOOL", "未知工具：" + name);
            record(context, name, args, result, started);
            return result;
        }
        if (!context.actor().role().atLeast(tool.minimumRole())) {
            result = ToolResult.fail("FORBIDDEN", "当前角色无权执行工具「" + name + "」");
            record(context, name, args, result, started);
            return result;
        }
        String validation = validate(tool.spec(), args);
        if (validation != null) {
            result = ToolResult.fail("VALIDATION_ERROR", validation);
            record(context, name, args, result, started);
            return result;
        }

        Future<ToolResult> future = executor.submit(() -> tool.execute(context, args == null ? Map.of() : args));
        try {
            result = future.get(Math.max(1, tool.timeoutSeconds(timeoutSeconds)), TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            result = ToolResult.fail("TIMEOUT", "工具「" + name + "」执行超时");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            result = ToolResult.fail("INTERRUPTED", "工具执行被中断");
        } catch (ExecutionException ex) {
            result = ToolResult.fail("EXECUTION_ERROR", "工具「" + name + "」执行失败");
        }
        record(context, name, args, result, started);
        return result;
    }

    /** Context-free calls are rejected so model-controlled resource ids cannot be trusted accidentally. */
    public ToolResult execute(String name, Map<String, Object> args) {
        return ToolResult.fail("CONTEXT_REQUIRED", "工具执行缺少受信任的文档与调用者上下文");
    }

    private String validate(ToolSpec spec, Map<String, Object> args) {
        Object raw = spec.parameters().get("required");
        if (raw instanceof List<?> required) {
            for (Object key : required) {
                Object value = args == null ? null : args.get(String.valueOf(key));
                if (value == null || (value instanceof String text && text.isBlank())
                        || (value instanceof java.util.Collection<?> collection && collection.isEmpty())) {
                    return "缺少必填参数：" + key;
                }
            }
        }
        Object rawProperties = spec.parameters().get("properties");
        if (!(rawProperties instanceof Map<?, ?> properties) || args == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            Object property = properties.get(entry.getKey());
            if (!(property instanceof Map<?, ?> definition)) {
                return "不支持的参数：" + entry.getKey();
            }
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            String type = String.valueOf(definition.get("type"));
            if (!matchesType(type, value)) {
                return "参数「" + entry.getKey() + "」类型应为 " + type;
            }
        }
        return null;
    }

    private static boolean matchesType(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long || value instanceof java.math.BigInteger
                    || value instanceof String text && text.matches("-?\\d+");
            case "number" -> value instanceof Number
                    || value instanceof String text && text.matches("-?\\d+(?:\\.\\d+)?");
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof java.util.Collection<?>;
            case "object" -> value instanceof Map<?, ?>;
            default -> false;
        };
    }

    private void record(
            ToolContext context,
            String name,
            Map<String, Object> args,
            ToolResult result,
            long startedNanos) {
        if (recorder != null && context != null && context.actor() != null) {
            recorder.record(
                    context, name, args == null ? Map.of() : args, result,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        }
    }
}
