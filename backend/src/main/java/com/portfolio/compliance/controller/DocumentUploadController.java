package com.portfolio.compliance.controller;

import com.portfolio.compliance.common.ApiResponse;
import com.portfolio.compliance.controller.dto.DocumentContentResponse;
import com.portfolio.compliance.controller.dto.DocumentListItem;
import com.portfolio.compliance.controller.dto.DocumentUploadResponse;
import com.portfolio.compliance.service.DocumentUploadService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/documents")
public class DocumentUploadController {

    private final DocumentUploadService uploadService;

    public DocumentUploadController(DocumentUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<DocumentListItem>> list() {
        return ApiResponse.ok(uploadService.listDocuments());
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<DocumentContentResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(uploadService.getDocument(id));
    }

    @PostMapping("/upload")
    @PreAuthorize("hasAnyRole('USER','REVIEWER','COMPLIANCE_ADMIN','SYSTEM_ADMIN')")
    public ApiResponse<DocumentUploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "docType", required = false) String docType) {
        return ApiResponse.ok(uploadService.upload(file, docType));
    }

    @PostMapping("/{id}/versions")
    @PreAuthorize("hasAnyRole('USER','REVIEWER','COMPLIANCE_ADMIN','SYSTEM_ADMIN')")
    public ApiResponse<DocumentUploadResponse> createVersion(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(uploadService.createVersion(id, file));
    }

    @GetMapping("/{id}/sections")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<com.portfolio.compliance.controller.dto.DocumentSectionResponse>> sections(
            @PathVariable Long id) {
        return ApiResponse.ok(uploadService.getSections(id));
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<DocumentListItem>> versions(@PathVariable Long id) {
        return ApiResponse.ok(uploadService.getVersions(id));
    }
}
