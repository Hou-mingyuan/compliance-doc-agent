package com.portfolio.compliance.agent.tool;

public record ToolResult(boolean ok, String code, String summary, Object data) {

    public static ToolResult ok(String summary, Object data) {
        return new ToolResult(true, "OK", summary, data);
    }

    public static ToolResult fail(String summary) {
        return fail("TOOL_ERROR", summary);
    }

    public static ToolResult fail(String code, String summary) {
        return new ToolResult(false, code, summary, null);
    }
}
