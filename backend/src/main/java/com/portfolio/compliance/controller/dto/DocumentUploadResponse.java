package com.portfolio.compliance.controller.dto;

import java.time.LocalDateTime;

public record DocumentUploadResponse(
        Long id,
        String title,
        String docType,
        String format,
        String status,
        int contentLength,
        int chunkCount,
        int pageCount,
        int versionNo,
        Long parentDocumentId,
        boolean duplicate,
        LocalDateTime createdAt) {
}
