package com.portfolio.compliance.controller;

import com.portfolio.compliance.common.ApiResponse;
import com.portfolio.compliance.controller.dto.AnalyzeRequest;
import com.portfolio.compliance.controller.dto.AnalyzeResponse;
import com.portfolio.compliance.service.ComplianceAnalysisService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/compliance")
public class ComplianceController {

    private final ComplianceAnalysisService analysisService;

    public ComplianceController(ComplianceAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @PostMapping("/analyze")
    public ApiResponse<AnalyzeResponse> analyze(@Valid @RequestBody AnalyzeRequest request) {
        return ApiResponse.ok(analysisService.analyze(request));
    }
}
