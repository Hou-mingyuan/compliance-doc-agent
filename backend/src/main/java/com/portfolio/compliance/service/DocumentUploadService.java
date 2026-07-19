package com.portfolio.compliance.service;

import java.time.LocalDateTime;
import java.util.List;

import com.portfolio.compliance.common.BizException;
import com.portfolio.compliance.controller.dto.DocumentContentResponse;
import com.portfolio.compliance.controller.dto.DocumentListItem;
import com.portfolio.compliance.controller.dto.DocumentUploadResponse;
import com.portfolio.compliance.entity.ComplianceDocument;
import com.portfolio.compliance.entity.ComplianceDocumentChunk;
import com.portfolio.compliance.mapper.ComplianceDocumentChunkMapper;
import com.portfolio.compliance.mapper.ComplianceDocumentMapper;
import com.portfolio.compliance.parser.DocumentParser;
import com.portfolio.compliance.parser.DocumentParser.ParsedDocument;
import com.portfolio.compliance.parser.TextChunker;
import com.portfolio.compliance.parser.TextChunker.TextChunk;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentUploadService {

    private final DocumentParser documentParser;
    private final TextChunker textChunker;
    private final ComplianceDocumentMapper documentMapper;
    private final ComplianceDocumentChunkMapper chunkMapper;

    public DocumentUploadService(
            DocumentParser documentParser,
            TextChunker textChunker,
            ComplianceDocumentMapper documentMapper,
            ComplianceDocumentChunkMapper chunkMapper) {
        this.documentParser = documentParser;
        this.textChunker = textChunker;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
    }

    @Transactional
    public DocumentUploadResponse upload(MultipartFile file, String docType) {
        ParsedDocument parsed = documentParser.parse(file);
        LocalDateTime now = LocalDateTime.now();

        ComplianceDocument doc = new ComplianceDocument();
        doc.setTitle(parsed.title());
        doc.setDocType(normalizeDocType(docType, parsed.format()));
        doc.setContent(parsed.content());
        doc.setStatus("UPLOADED");
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        documentMapper.insert(doc);

        List<TextChunk> chunks = textChunker.chunk(parsed.content());
        persistChunks(doc.getId(), chunks, now);

        return new DocumentUploadResponse(
                doc.getId(),
                doc.getTitle(),
                doc.getDocType(),
                parsed.format(),
                doc.getStatus(),
                parsed.content().length(),
                chunks.size(),
                doc.getCreatedAt());
    }

    public List<DocumentListItem> listDocuments() {
        QueryWrapper<ComplianceDocument> q = new QueryWrapper<>();
        q.orderByDesc("created_at");
        return documentMapper.selectList(q).stream()
                .map(doc -> DocumentListItem.from(new DocumentListItem.ComplianceDocumentView(
                        doc.getId(),
                        doc.getTitle(),
                        doc.getDocType(),
                        doc.getStatus(),
                        doc.getContent() != null ? doc.getContent().length() : 0,
                        doc.getCreatedAt())))
                .toList();
    }

    public DocumentContentResponse getDocument(Long id) {
        ComplianceDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new BizException(404, "文档不存在：" + id);
        }
        String content = doc.getContent() == null ? "" : doc.getContent();
        return new DocumentContentResponse(
                doc.getId(),
                doc.getTitle(),
                doc.getDocType(),
                doc.getStatus(),
                content,
                content.length(),
                doc.getCreatedAt());
    }

    private void persistChunks(Long documentId, List<TextChunk> chunks, LocalDateTime now) {
        for (TextChunk chunk : chunks) {
            ComplianceDocumentChunk row = new ComplianceDocumentChunk();
            row.setDocumentId(documentId);
            row.setChunkIndex(chunk.index());
            row.setContent(chunk.content());
            row.setCharStart(chunk.charStart());
            row.setCharEnd(chunk.charEnd());
            row.setCreatedAt(now);
            chunkMapper.insert(row);
        }
    }

    private static String normalizeDocType(String docType, String format) {
        if (StringUtils.hasText(docType)) {
            return docType.trim().toUpperCase();
        }
        if ("pdf".equals(format)) {
            return "CONTRACT";
        }
        return "md".equals(format) || "markdown".equals(format) ? "POLICY" : "GENERAL";
    }
}
