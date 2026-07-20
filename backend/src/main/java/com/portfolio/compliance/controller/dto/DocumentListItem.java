package com.portfolio.compliance.controller.dto;

import java.time.LocalDateTime;

/** 与前端 DocumentItem 对齐的文档列表项。 */
public record DocumentListItem(
        String id,
        String filename,
        long size,
        String contentType,
        String status,
        String uploadedAt,
        String docType,
        String tenantId,
        int pageCount,
        int versionNo,
        Long parentDocumentId) {

    public static DocumentListItem from(ComplianceDocumentView doc) {
        return new DocumentListItem(
                String.valueOf(doc.id()),
                doc.title(),
                doc.contentLength(),
                doc.fileFormat() != null ? doc.fileFormat() : "txt",
                mapStatus(doc.status()),
                doc.createdAt() != null ? doc.createdAt().toString() : "",
                doc.docType(),
                doc.tenantId(),
                doc.pageCount() == null ? 0 : doc.pageCount(),
                doc.versionNo() == null ? 1 : doc.versionNo(),
                doc.parentDocumentId());
    }

    private static String mapStatus(String backendStatus) {
        if (backendStatus == null) {
            return "uploaded";
        }
        return switch (backendStatus.toUpperCase()) {
            case "REVIEWING", "RUNNING", "AUDITING" -> "auditing";
            case "PENDING_REVIEW" -> "pending_review";
            case "REMEDIATION" -> "remediation";
            case "APPROVED", "DONE" -> "done";
            case "CANCELLED" -> "cancelled";
            case "FAILED" -> "failed";
            default -> "uploaded";
        };
    }

    public record ComplianceDocumentView(
            Long id,
            String title,
            String docType,
            String fileFormat,
            String status,
            int contentLength,
            String tenantId,
            Integer pageCount,
            Integer versionNo,
            Long parentDocumentId,
            LocalDateTime createdAt) {
    }
}
