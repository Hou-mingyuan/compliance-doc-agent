package com.portfolio.compliance.agent.tool;

/** 工具执行结果。 */
public record ToolResult(boolean ok, String summary, Object data) {

    public static ToolResult ok(String summary, Object data) {
        return new ToolResult(true, summary, data);
    }

    public static ToolResult fail(String summary) {
        return new ToolResult(false, summary, null);
    }
}
