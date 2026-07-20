package com.portfolio.compliance.service;

import java.time.LocalDateTime;
import java.util.List;

import com.portfolio.compliance.agent.ComplianceAgentOrchestrator;
import com.portfolio.compliance.agent.ComplianceAgentOrchestrator.AgentAnalysisResult;
import com.portfolio.compliance.controller.dto.AnalyzeRequest;
import com.portfolio.compliance.controller.dto.AnalyzeResponse;
import com.portfolio.compliance.entity.ComplianceCheck;
import com.portfolio.compliance.entity.ComplianceDocument;
import com.portfolio.compliance.entity.ComplianceDocumentChunk;
import com.portfolio.compliance.mapper.ComplianceDocumentChunkMapper;
import com.portfolio.compliance.mapper.ComplianceCheckMapper;
import com.portfolio.compliance.mapper.ComplianceDocumentMapper;
import com.portfolio.compliance.parser.TextChunker;
import com.portfolio.compliance.security.ActorContext;
import com.portfolio.compliance.security.ActorContextProvider;
import com.portfolio.compliance.common.Hashing;
import com.portfolio.compliance.rules.ComplianceRule;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ComplianceAnalysisService {

    private final ComplianceDocumentMapper documentMapper;
    private final ComplianceCheckMapper checkMapper;
    private final ComplianceDocumentChunkMapper chunkMapper;
    private final ComplianceAgentOrchestrator orchestrator;
    private final TextChunker chunker;
    private final ActorContextProvider actors;

    public ComplianceAnalysisService(
            ComplianceDocumentMapper documentMapper,
            ComplianceCheckMapper checkMapper,
            ComplianceDocumentChunkMapper chunkMapper,
            ComplianceAgentOrchestrator orchestrator,
            TextChunker chunker,
            ActorContextProvider actors) {
        this.documentMapper = documentMapper;
        this.checkMapper = checkMapper;
        this.chunkMapper = chunkMapper;
        this.orchestrator = orchestrator;
        this.chunker = chunker;
        this.actors = actors;
    }

    @Transactional
    public AnalyzeResponse analyze(AnalyzeRequest req) {
        ActorContext actor = actors.current();
        String docType = req.getDocType() == null ? "GENERAL" : req.getDocType().strip().toUpperCase();
        LocalDateTime now = LocalDateTime.now();
        ComplianceDocument doc = new ComplianceDocument();
        doc.setTenantId(actor.tenantId());
        doc.setOwnerId(actor.userId());
        doc.setTitle(req.getTitle().strip());
        doc.setSourceFilename("inline-analysis.txt");
        doc.setDocType(docType);
        doc.setFileFormat("txt");
        doc.setSha256(Hashing.sha256(req.getContent()));
        doc.setContent(req.getContent());
        doc.setPageCount(1);
        doc.setVersionNo(1);
        doc.setStatus("PARSED");
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        documentMapper.insert(doc);

        persistChunks(doc.getId(), req.getContent(), now);
        AgentAnalysisResult result = orchestrator.analyze(req.getTitle(), req.getContent(), docType);
        persistRuleHits(doc.getId(), result.ruleHits());

        return new AnalyzeResponse(
                doc.getId(),
                result.maxSeverity(),
                result.llmProvider(),
                result.ruleHits(),
                result.findings(),
                result.toolTrace(),
                result.llmSummary());
    }

    private void persistChunks(Long documentId, String content, LocalDateTime now) {
        int index = 0;
        for (TextChunker.TextChunk chunk : chunker.chunk(content)) {
            ComplianceDocumentChunk row = new ComplianceDocumentChunk();
            row.setDocumentId(documentId);
            row.setChunkIndex(index++);
            row.setPageNo(1);
            row.setParagraphNo(1);
            row.setContent(chunk.content());
            row.setCharStart(chunk.charStart());
            row.setCharEnd(chunk.charEnd());
            row.setCreatedAt(now);
            chunkMapper.insert(row);
        }
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
