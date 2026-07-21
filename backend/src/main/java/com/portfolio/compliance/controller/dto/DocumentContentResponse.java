package com.portfolio.compliance.controller.dto;

import java.time.LocalDateTime;

public record DocumentContentResponse(
        Long id,
        String title,
        String sourceFilename,
        String docType,
        String format,
        String status,
        String content,
        int contentLength,
        int pageCount,
        int versionNo,
        Long parentDocumentId,
        LocalDateTime createdAt) {
}
