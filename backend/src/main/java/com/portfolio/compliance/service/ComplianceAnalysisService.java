package com.portfolio.compliance.service;

import java.time.LocalDateTime;
import java.util.List;

import com.portfolio.compliance.agent.ComplianceAgentOrchestrator;
import com.portfolio.compliance.agent.ComplianceAgentOrchestrator.AgentAnalysisResult;
import com.portfolio.compliance.controller.dto.AnalyzeRequest;
import com.portfolio.compliance.controller.dto.AnalyzeResponse;
import com.portfolio.compliance.entity.ComplianceCheck;
import com.portfolio.compliance.entity.ComplianceDocument;
import com.portfolio.compliance.mapper.ComplianceCheckMapper;
import com.portfolio.compliance.mapper.ComplianceDocumentMapper;
import com.portfolio.compliance.rules.ComplianceRule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ComplianceAnalysisService {

    private final ComplianceDocumentMapper documentMapper;
    private final ComplianceCheckMapper checkMapper;
    private final ComplianceAgentOrchestrator orchestrator;

    public ComplianceAnalysisService(
            ComplianceDocumentMapper documentMapper,
            ComplianceCheckMapper checkMapper,
            ComplianceAgentOrchestrator orchestrator) {
        this.documentMapper = documentMapper;
        this.checkMapper = checkMapper;
        this.orchestrator = orchestrator;
    }

    @Transactional
    public AnalyzeResponse analyze(AnalyzeRequest req) {
        ComplianceDocument doc = new ComplianceDocument();
        doc.setTitle(req.getTitle());
        doc.setDocType(req.getDocType());
        doc.setContent(req.getContent());
        doc.setStatus("ANALYZING");
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        documentMapper.insert(doc);

        AgentAnalysisResult result = orchestrator.analyze(req.getTitle(), req.getContent());
        persistRuleHits(doc.getId(), result.ruleHits());

        doc.setStatus("DONE");
        doc.setUpdatedAt(LocalDateTime.now());
        documentMapper.updateById(doc);

        return new AnalyzeResponse(
                doc.getId(),
                result.maxSeverity(),
                result.llmProvider(),
                result.ruleHits(),
                result.findings(),
                result.toolTrace(),
                result.llmSummary());
    }

    private void persistRuleHits(Long documentId, List<ComplianceRule> rules) {
        LocalDateTime now = LocalDateTime.now();
        for (ComplianceRule rule : rules) {
            ComplianceCheck check = new ComplianceCheck();
            check.setDocumentId(documentId);
            check.setRuleCode(rule.code());
            check.setSeverity(rule.severity().name());
            check.setMessage(rule.message());
            check.setCreatedAt(now);
            checkMapper.insert(check);
        }
    }
}
