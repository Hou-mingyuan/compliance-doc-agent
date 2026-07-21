package com.portfolio.compliance.controller;

import java.time.LocalDate;
import java.util.List;

import com.portfolio.compliance.common.ApiResponse;
import com.portfolio.compliance.knowledge.RegulationCatalog;
import com.portfolio.compliance.knowledge.RegulationCatalog.RegulationEntry;
import com.portfolio.compliance.knowledge.RegulationCatalog.RegulationMatch;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/regulations")
@PreAuthorize("isAuthenticated()")
public class RegulationController {

    private final RegulationCatalog catalog;

    public RegulationController(RegulationCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public ApiResponse<List<RegulationEntry>> list() {
        return ApiResponse.ok(catalog.list());
    }

    @GetMapping("/search")
    public ApiResponse<List<RegulationMatch>> search(
            @RequestParam String query,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(defaultValue = "3") int topK) {
        return ApiResponse.ok(catalog.search(query, scope, asOf, topK));
    }
}
