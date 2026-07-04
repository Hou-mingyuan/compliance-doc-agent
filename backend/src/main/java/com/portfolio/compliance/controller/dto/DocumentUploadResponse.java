package com.portfolio.compliance.controller.dto;

import java.time.LocalDateTime;

public record DocumentUploadResponse(
        Long id,
        String title,
        String docType,
        String format,
        String status,
        int contentLength,
        LocalDateTime createdAt) {
}
