package com.portfolio.compliance.agent.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.portfolio.compliance.llm.ToolSpec;
import org.springframework.stereotype.Component;

/** 工具注册中心：聚合所有 AgentTool，提供 Schema 列表与按名执行。 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AgentTool> toolList) {
        for (AgentTool t : toolList) {
            tools.put(t.name(), t);
        }
    }

    public List<ToolSpec> specs() {
        return tools.values().stream().map(AgentTool::spec).toList();
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    public ToolResult execute(String name, Map<String, Object> args) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            return ToolResult.fail("未知工具：" + name);
        }
        try {
            return tool.execute(args);
        } catch (Exception e) {
            return ToolResult.fail("工具「" + name + "」执行异常：" + e.getMessage());
        }
    }
}
