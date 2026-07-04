package com.portfolio.compliance.service;

import java.time.LocalDateTime;

import com.portfolio.compliance.controller.dto.DocumentUploadResponse;
import com.portfolio.compliance.entity.ComplianceDocument;
import com.portfolio.compliance.mapper.ComplianceDocumentMapper;
import com.portfolio.compliance.parser.DocumentParser;
import com.portfolio.compliance.parser.DocumentParser.ParsedDocument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentUploadService {

    private final DocumentParser documentParser;
    private final ComplianceDocumentMapper documentMapper;

    public DocumentUploadService(DocumentParser documentParser, ComplianceDocumentMapper documentMapper) {
        this.documentParser = documentParser;
        this.documentMapper = documentMapper;
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

        return new DocumentUploadResponse(
                doc.getId(),
                doc.getTitle(),
                doc.getDocType(),
                parsed.format(),
                doc.getStatus(),
                parsed.content().length(),
                doc.getCreatedAt());
    }

    private static String normalizeDocType(String docType, String format) {
        if (StringUtils.hasText(docType)) {
            return docType.trim().toUpperCase();
        }
        return "md".equals(format) || "markdown".equals(format) ? "POLICY" : "GENERAL";
    }
}
