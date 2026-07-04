package com.portfolio.compliance.agent.tool;

import java.util.Map;

import com.portfolio.compliance.llm.ToolSpec;

/** Agent 可调用的合规审查工具。 */
public interface AgentTool {

    String name();

    ToolSpec spec();

    ToolResult execute(Map<String, Object> args);
}
