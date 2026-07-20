package com.portfolio.compliance.controller.dto;

public record DocumentSectionResponse(
        Long id,
        int index,
        Integer pageNo,
        String sectionTitle,
        Integer paragraphNo,
        int charStart,
        int charEnd,
        String content) {
}
