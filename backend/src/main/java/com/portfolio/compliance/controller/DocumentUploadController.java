package com.portfolio.compliance.controller;

import com.portfolio.compliance.common.ApiResponse;
import com.portfolio.compliance.controller.dto.DocumentUploadResponse;
import com.portfolio.compliance.service.DocumentUploadService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
public class DocumentUploadController {

    private final DocumentUploadService uploadService;

    public DocumentUploadController(DocumentUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping("/upload")
    public ApiResponse<DocumentUploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "docType", required = false) String docType) {
        return ApiResponse.ok(uploadService.upload(file, docType));
    }
}
