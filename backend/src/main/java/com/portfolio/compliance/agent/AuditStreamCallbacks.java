package com.portfolio.compliance.agent;

import com.portfolio.compliance.agent.tool.ToolResult;
import com.portfolio.compliance.workflow.ReviewStore.FindingRecord;

public interface AuditStreamCallbacks {

    void onFinding(FindingRecord finding);

    void onToken(String token);

    default void onTool(String toolName, ToolResult result) {
    }

    default void onStage(String stage, String message) {
    }

    default boolean isCancelled() {
        return false;
    }
}
