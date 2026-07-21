package com.portfolio.compliance.controller;

import java.time.LocalDate;
import java.util.List;

import com.portfolio.compliance.common.ApiResponse;
import com.portfolio.compliance.security.ActorContextProvider;
import com.portfolio.compliance.workflow.RemediationService;
import com.portfolio.compliance.workflow.RemediationService.RemediationRecord;
import com.portfolio.compliance.workflow.RemediationService.RemediationDetail;
import jakarta.validation.Valid;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/remediations")
public class RemediationController {

    private final RemediationService remediations;
    private final ActorContextProvider actors;

    public RemediationController(RemediationService remediations, ActorContextProvider actors) {
        this.remediations = remediations;
        this.actors = actors;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<RemediationRecord>> list(@RequestParam(required = false) Long reviewId) {
        return ApiResponse.ok(remediations.list(actors.current(), reviewId));
    }

    @GetMapping("/{taskKey}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<RemediationDetail> detail(@PathVariable String taskKey) {
        return ApiResponse.ok(remediations.detail(taskKey, actors.current()));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('COMPLIANCE_ADMIN','SYSTEM_ADMIN')")
    public ApiResponse<RemediationRecord> create(@Valid @RequestBody CreateRequest request) {
        return ApiResponse.ok(remediations.create(
                actors.current(), request.findingKey(), request.assigneeId(), request.dueDate(), request.description()));
    }

    @PostMapping("/{taskKey}/start")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<RemediationRecord> start(@PathVariable String taskKey) {
        return ApiResponse.ok(remediations.start(taskKey, actors.current()));
    }

    @PostMapping("/{taskKey}/evidence")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<RemediationRecord> evidence(
            @PathVariable String taskKey,
            @Valid @RequestBody EvidenceRequest request) {
        return ApiResponse.ok(remediations.submitEvidence(taskKey, request.evidenceText(), actors.current()));
    }

    @PostMapping("/{taskKey}/review")
    @PreAuthorize("hasAnyRole('REVIEWER','COMPLIANCE_ADMIN','SYSTEM_ADMIN')")
    public ApiResponse<RemediationRecord> review(
            @PathVariable String taskKey,
            @Valid @RequestBody EvidenceReviewRequest request) {
        return ApiResponse.ok(remediations.reviewEvidence(
                taskKey, request.approved(), request.comment(), actors.current()));
    }

    @PostMapping("/{taskKey}/close")
    @PreAuthorize("hasAnyRole('COMPLIANCE_ADMIN','SYSTEM_ADMIN')")
    public ApiResponse<RemediationRecord> close(@PathVariable String taskKey) {
        return ApiResponse.ok(remediations.close(taskKey, actors.current()));
    }

    @PostMapping("/{taskKey}/reopen")
    @PreAuthorize("hasAnyRole('COMPLIANCE_ADMIN','SYSTEM_ADMIN')")
    public ApiResponse<RemediationRecord> reopen(
            @PathVariable String taskKey,
            @Valid @RequestBody ReopenRequest request) {
        return ApiResponse.ok(remediations.reopen(taskKey, request.reason(), actors.current()));
    }

    public record CreateRequest(
            @NotBlank(message = "风险项不能为空") String findingKey,
            @NotBlank(message = "整改负责人不能为空") String assigneeId,
            @NotNull(message = "截止日期不能为空") @FutureOrPresent LocalDate dueDate,
            @NotBlank(message = "整改要求不能为空") @Size(max = 2000) String description) {
    }

    public record EvidenceRequest(
            @NotBlank(message = "整改证据不能为空") @Size(max = 4000) String evidenceText) {
    }

    public record EvidenceReviewRequest(
            boolean approved,
            @NotBlank(message = "复审意见不能为空") @Size(max = 2000) String comment) {
    }

    public record ReopenRequest(
            @NotBlank(message = "重开原因不能为空") @Size(max = 1000) String reason) {
    }
}
