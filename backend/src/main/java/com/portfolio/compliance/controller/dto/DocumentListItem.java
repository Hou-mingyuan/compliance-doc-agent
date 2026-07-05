package com.portfolio.compliance.controller.dto;

import java.time.LocalDateTime;

/** 与前端 DocumentItem 对齐的文档列表项。 */
public record DocumentListItem(
        String id,
        String filename,
        long size,
        String contentType,
        String status,
        String uploadedAt) {

    public static DocumentListItem from(ComplianceDocumentView doc) {
        return new DocumentListItem(
                String.valueOf(doc.id()),
                doc.title(),
                doc.contentLength(),
                doc.docType() != null ? doc.docType().toLowerCase() : "general",
                mapStatus(doc.status()),
                doc.createdAt() != null ? doc.createdAt().toString() : "");
    }

    private static String mapStatus(String backendStatus) {
        if (backendStatus == null) {
            return "uploaded";
        }
        return switch (backendStatus.toUpperCase()) {
            case "AUDITING" -> "auditing";
            case "DONE" -> "done";
            case "FAILED" -> "failed";
            default -> "uploaded";
        };
    }

    public record ComplianceDocumentView(
            Long id,
            String title,
            String docType,
            String status,
            int contentLength,
            LocalDateTime createdAt) {
    }
}
