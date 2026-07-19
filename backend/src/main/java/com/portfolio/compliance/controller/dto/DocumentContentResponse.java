package com.portfolio.compliance.controller.dto;

import java.time.LocalDateTime;

public record DocumentContentResponse(
        Long id,
        String title,
        String docType,
        String status,
        String content,
        int contentLength,
        LocalDateTime createdAt) {
}
