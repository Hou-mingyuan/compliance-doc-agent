package com.portfolio.compliance.controller;

import java.util.List;

import com.portfolio.compliance.common.ApiResponse;
import com.portfolio.compliance.security.ActorContext;
import com.portfolio.compliance.security.ActorContextProvider;
import com.portfolio.compliance.workflow.ReviewQueryService;
import com.portfolio.compliance.workflow.ReviewQueryService.ReviewDetail;
import com.portfolio.compliance.workflow.ReviewStore.FindingRecord;
import com.portfolio.compliance.workflow.ReviewStore.ReviewRecord;
import com.portfolio.compliance.workflow.ReviewWorkflowService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ReviewController {

    private final ReviewQueryService queries;
    private final ReviewWorkflowService workflow;
    private final ActorContextProvider actors;

    public ReviewController(ReviewQueryService queries, ReviewWorkflowService workflow, ActorContextProvider actors) {
        this.queries = queries;
        this.workflow = workflow;
        this.actors = actors;
    }

    @GetMapping("/reviews")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<ReviewRecord>> list() {
        return ApiResponse.ok(queries.list(actors.current()));
    }

    @GetMapping("/reviews/{reviewKey}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ReviewDetail> detail(@PathVariable String reviewKey) {
        return ApiResponse.ok(queries.detail(reviewKey, actors.current()));
    }

    @PostMapping("/reviews/{reviewKey}/cancel")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ReviewRecord> cancel(@PathVariable String reviewKey) {
        return ApiResponse.ok(workflow.requestCancel(reviewKey, actors.current()));
    }

    @PostMapping("/reviews/{reviewKey}/approve")
    @PreAuthorize("hasAnyRole('REVIEWER','COMPLIANCE_ADMIN','SYSTEM_ADMIN')")
    public ApiResponse<ReviewRecord> approve(
            @PathVariable String reviewKey,
            @Valid @RequestBody ApprovalRequest request) {
        return ApiResponse.ok(workflow.approve(reviewKey, request.comment(), actors.current()));
    }

    @PostMapping("/findings/{findingKey}/review")
    @PreAuthorize("hasAnyRole('REVIEWER','COMPLIANCE_ADMIN','SYSTEM_ADMIN')")
    public ApiResponse<FindingRecord> reviewFinding(
            @PathVariable String findingKey,
            @Valid @RequestBody FindingReviewRequest request) {
        boolean confirmed = switch (request.decision().strip().toUpperCase()) {
            case "CONFIRM", "CONFIRMED" -> true;
            case "FALSE_POSITIVE", "REJECT" -> false;
            default -> throw new com.portfolio.compliance.common.BizException(
                    "decision 必须是 CONFIRM 或 FALSE_POSITIVE");
        };
        return ApiResponse.ok(workflow.reviewFinding(findingKey, confirmed, request.comment(), actors.current()));
    }

    public record FindingReviewRequest(
            @NotBlank(message = "复核决定不能为空") String decision,
            @NotBlank(message = "复核意见不能为空") String comment) {
    }

    public record ApprovalRequest(@NotBlank(message = "批准意见不能为空") String comment) {
    }
}
