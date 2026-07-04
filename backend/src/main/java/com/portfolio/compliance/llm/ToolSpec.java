package com.portfolio.compliance.llm;

import java.util.Map;

/** 工具（function）声明，用于传给支持 Function Calling 的模型。 */
public record ToolSpec(String name, String description, Map<String, Object> parameters) {
}
