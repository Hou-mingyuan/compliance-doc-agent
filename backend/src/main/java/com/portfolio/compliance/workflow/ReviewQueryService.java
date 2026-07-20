package com.portfolio.compliance.workflow;

import java.util.List;

import com.portfolio.compliance.agent.tool.ToolExecutionRecorder;
import com.portfolio.compliance.agent.tool.ToolExecutionRecorder.ToolExecutionRecord;
import com.portfolio.compliance.controller.dto.DocumentContentResponse;
import com.portfolio.compliance.entity.ComplianceDocument;
import com.portfolio.compliance.report.ReportService;
import com.portfolio.compliance.report.ReportService.ReportMetadata;
import com.portfolio.compliance.security.ActorContext;
import com.portfolio.compliance.service.DocumentUploadService;
import com.portfolio.compliance.workflow.RemediationService.RemediationRecord;
import com.portfolio.compliance.workflow.ReviewStore.CitationRecord;
import com.portfolio.compliance.workflow.ReviewStore.EntityRecord;
import com.portfolio.compliance.workflow.ReviewStore.FindingRecord;
import com.portfolio.compliance.workflow.ReviewStore.ReviewRecord;
import org.springframework.stereotype.Service;

@Service
public class ReviewQueryService {

    private final ReviewStore reviews;
    private final DocumentUploadService documents;
    private final RemediationService remediations;
    private final ToolExecutionRecorder toolExecutions;
    private final ReportService reports;

    public ReviewQueryService(
            ReviewStore reviews,
            DocumentUploadService documents,
            RemediationService remediations,
            ToolExecutionRecorder toolExecutions,
            ReportService reports) {
        this.reviews = reviews;
        this.documents = documents;
        this.remediations = remediations;
        this.toolExecutions = toolExecutions;
        this.reports = reports;
    }

    public List<ReviewRecord> list(ActorContext actor) {
        return reviews.listReviews(actor);
    }

    public ReviewDetail detail(String reviewKey, ActorContext actor) {
        ReviewRecord review = reviews.requireReview(reviewKey, actor);
        ComplianceDocument document = documents.requireDocument(review.documentId(), actor);
        String content = document.getContent() == null ? "" : document.getContent();
        DocumentContentResponse documentView = new DocumentContentResponse(
                document.getId(), document.getTitle(), document.getSourceFilename(), document.getDocType(),
                document.getFileFormat(), document.getStatus(), content, content.length(),
                document.getPageCount() == null ? 0 : document.getPageCount(),
                document.getVersionNo() == null ? 1 : document.getVersionNo(), document.getParentDocumentId(),
                document.getCreatedAt());
        List<FindingView> findings = reviews.listFindings(review.id()).stream()
                .map(item -> new FindingView(item, reviews.listCitations(item.id())))
                .toList();
        List<EntityRecord> entities = reviews.listEntities(review.id());
        List<RemediationRecord> tasks = remediations.list(actor, review.id());
        List<ToolExecutionRecord> tools = toolExecutions.list(review.id());
        List<ReportMetadata> reportList = reports.listForReview(reviewKey, actor);
        return new ReviewDetail(review, documentView, findings, entities, tasks, tools, reportList);
    }

    public record FindingView(FindingRecord finding, List<CitationRecord> citations) {
    }

    public record ReviewDetail(
            ReviewRecord review,
            DocumentContentResponse document,
            List<FindingView> findings,
            List<EntityRecord> entities,
            List<RemediationRecord> remediations,
            List<ToolExecutionRecord> toolExecutions,
            List<ReportMetadata> reports) {
    }
}
