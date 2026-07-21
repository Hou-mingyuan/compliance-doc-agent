package com.portfolio.compliance.controller;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.portfolio.compliance.common.ApiResponse;
import com.portfolio.compliance.report.ReportService;
import com.portfolio.compliance.report.ReportService.ReportMetadata;
import com.portfolio.compliance.security.ActorContextProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
@PreAuthorize("hasAnyRole('REVIEWER','COMPLIANCE_ADMIN','SYSTEM_ADMIN')")
public class ReportController {

    private static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final ReportService reports;
    private final ActorContextProvider actors;

    public ReportController(ReportService reports, ActorContextProvider actors) {
        this.reports = reports;
        this.actors = actors;
    }

    @PostMapping
    public ApiResponse<ReportMetadata> generate(@Valid @RequestBody GenerateRequest request) {
        return ApiResponse.ok(reports.generate(request.reviewKey(), actors.current()));
    }

    @GetMapping("/{reportKey}")
    public ApiResponse<ReportMetadata> get(@PathVariable String reportKey) {
        return ApiResponse.ok(reports.get(reportKey, actors.current()));
    }

    @GetMapping("/review/{reviewKey}")
    public ApiResponse<List<ReportMetadata>> list(@PathVariable String reviewKey) {
        return ApiResponse.ok(reports.listForReview(reviewKey, actors.current()));
    }

    @GetMapping("/{reportKey}/download")
    public ResponseEntity<byte[]> download(@PathVariable String reportKey) {
        var file = reports.download(reportKey, actors.current());
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(DOCX)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-SHA256", file.sha256())
                .contentLength(file.content().length)
                .body(file.content());
    }

    public record GenerateRequest(@NotBlank(message = "审核运行不能为空") String reviewKey) {
    }
}
