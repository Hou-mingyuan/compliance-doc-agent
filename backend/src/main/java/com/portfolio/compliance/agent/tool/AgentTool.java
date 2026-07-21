package com.portfolio.compliance.agent.tool;

import java.util.Map;

import com.portfolio.compliance.llm.ToolSpec;
import com.portfolio.compliance.security.AppRole;

/** Agent 可调用的合规审查工具。 */
public interface AgentTool {

    String name();

    ToolSpec spec();

    AppRole minimumRole();

    default int timeoutSeconds(int configuredDefault) {
        return configuredDefault;
    }

    ToolResult execute(ToolContext context, Map<String, Object> args);
}
