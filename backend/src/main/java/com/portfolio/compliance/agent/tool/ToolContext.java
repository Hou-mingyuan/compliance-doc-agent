package com.portfolio.compliance.agent.tool;

import com.portfolio.compliance.security.ActorContext;

public record ToolContext(
        ActorContext actor,
        Long reviewId,
        Long documentId,
        String documentType,
        String documentTitle,
        String documentContent) {
}
