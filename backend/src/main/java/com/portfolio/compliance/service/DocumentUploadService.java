package com.portfolio.compliance.service;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.portfolio.compliance.common.BizException;
import com.portfolio.compliance.audit.AuditTrail;
import com.portfolio.compliance.controller.dto.DocumentContentResponse;
import com.portfolio.compliance.controller.dto.DocumentListItem;
import com.portfolio.compliance.controller.dto.DocumentSectionResponse;
import com.portfolio.compliance.controller.dto.DocumentUploadResponse;
import com.portfolio.compliance.entity.ComplianceDocument;
import com.portfolio.compliance.entity.ComplianceDocumentChunk;
import com.portfolio.compliance.mapper.ComplianceDocumentChunkMapper;
import com.portfolio.compliance.mapper.ComplianceDocumentMapper;
import com.portfolio.compliance.parser.DocumentParser;
import com.portfolio.compliance.parser.DocumentParser.ParsedDocument;
import com.portfolio.compliance.parser.DocumentParser.ParsedSection;
import com.portfolio.compliance.parser.TextChunker;
import com.portfolio.compliance.parser.TextChunker.TextChunk;
import com.portfolio.compliance.security.ActorContext;
import com.portfolio.compliance.security.ActorContextProvider;
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
    private final ActorContextProvider actors;
    private final AuditTrail audit;

    public DocumentUploadService(
            DocumentParser documentParser,
            TextChunker textChunker,
            ComplianceDocumentMapper documentMapper,
            ComplianceDocumentChunkMapper chunkMapper,
            ActorContextProvider actors,
            AuditTrail audit) {
        this.documentParser = documentParser;
        this.textChunker = textChunker;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.actors = actors;
        this.audit = audit;
    }

    @Transactional
    public DocumentUploadResponse upload(MultipartFile file, String docType) {
        ActorContext actor = actors.current();
        ParsedDocument parsed = documentParser.parse(file);
        ComplianceDocument duplicate = documentMapper.selectOne(new QueryWrapper<ComplianceDocument>()
                .eq("tenant_id", actor.tenantId())
                .eq("sha256", parsed.sha256())
                .isNull("parent_document_id")
                .last("LIMIT 1"));
        if (duplicate != null) {
            return toUploadResponse(duplicate, countChunks(duplicate.getId()), true);
        }
        return persist(parsed, normalizeDocType(docType, parsed.format()), actor, null, 1, false);
    }

    @Transactional
    public DocumentUploadResponse createVersion(Long documentId, MultipartFile file) {
        ActorContext actor = actors.current();
        ComplianceDocument base = requireDocument(documentId, actor);
        ParsedDocument parsed = documentParser.parse(file);
        Long rootId = base.getParentDocumentId() == null ? base.getId() : base.getParentDocumentId();
        String resourceTenantId = base.getTenantId();

        QueryWrapper<ComplianceDocument> duplicateQuery = new QueryWrapper<>();
        duplicateQuery.eq("tenant_id", resourceTenantId).eq("sha256", parsed.sha256())
                .and(q -> q.eq("id", rootId).or().eq("parent_document_id", rootId))
                .last("LIMIT 1");
        ComplianceDocument duplicate = documentMapper.selectOne(duplicateQuery);
        if (duplicate != null) {
            return toUploadResponse(duplicate, countChunks(duplicate.getId()), true);
        }

        QueryWrapper<ComplianceDocument> versions = new QueryWrapper<>();
        versions.eq("tenant_id", resourceTenantId)
                .and(q -> q.eq("id", rootId).or().eq("parent_document_id", rootId))
                .orderByDesc("version_no")
                .last("LIMIT 1");
        ComplianceDocument latest = documentMapper.selectOne(versions);
        int nextVersion = latest == null || latest.getVersionNo() == null ? 2 : latest.getVersionNo() + 1;
        ActorContext resourceActor = new ActorContext(actor.userId(), resourceTenantId, actor.role());
        return persist(parsed, base.getDocType(), resourceActor, rootId, nextVersion, false);
    }

    public List<DocumentListItem> listDocuments() {
        ActorContext actor = actors.current();
        QueryWrapper<ComplianceDocument> query = new QueryWrapper<>();
        if (actor.role() != com.portfolio.compliance.security.AppRole.SYSTEM_ADMIN) {
            query.eq("tenant_id", actor.tenantId());
        }
        query.orderByDesc("updated_at");
        List<ComplianceDocument> documents = documentMapper.selectList(query);
        documents.forEach(document -> audit.recordCrossTenantRead(
                actor, document.getTenantId(), "document", String.valueOf(document.getId())));
        return documents.stream().map(this::toListItem).toList();
    }

    public DocumentContentResponse getDocument(Long id) {
        return toContentResponse(requireDocument(id, actors.current()));
    }

    public List<DocumentSectionResponse> getSections(Long id) {
        ComplianceDocument doc = requireDocument(id, actors.current());
        return chunkMapper.selectList(new QueryWrapper<ComplianceDocumentChunk>()
                        .eq("document_id", doc.getId())
                        .orderByAsc("chunk_index"))
                .stream()
                .map(row -> new DocumentSectionResponse(
                        row.getId(), row.getChunkIndex(), row.getPageNo(), row.getSectionTitle(), row.getParagraphNo(),
                        value(row.getCharStart()), value(row.getCharEnd()), row.getContent()))
                .toList();
    }

    public List<DocumentListItem> getVersions(Long id) {
        ActorContext actor = actors.current();
        ComplianceDocument doc = requireDocument(id, actor);
        Long rootId = doc.getParentDocumentId() == null ? doc.getId() : doc.getParentDocumentId();
        QueryWrapper<ComplianceDocument> query = new QueryWrapper<>();
        query.eq("tenant_id", doc.getTenantId())
                .and(q -> q.eq("id", rootId).or().eq("parent_document_id", rootId))
                .orderByAsc("version_no");
        return documentMapper.selectList(query).stream().map(this::toListItem).toList();
    }

    public ComplianceDocument requireDocument(Long id, ActorContext actor) {
        ComplianceDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new BizException(404, "文档不存在");
        }
        actor.requireTenant(doc.getTenantId());
        audit.recordCrossTenantRead(actor, doc.getTenantId(), "document", String.valueOf(doc.getId()));
        return doc;
    }

    private DocumentUploadResponse persist(
            ParsedDocument parsed,
            String docType,
            ActorContext actor,
            Long parentDocumentId,
            int versionNo,
            boolean duplicate) {
        LocalDateTime now = LocalDateTime.now();
        ComplianceDocument doc = new ComplianceDocument();
        doc.setTenantId(actor.tenantId());
        doc.setOwnerId(actor.userId());
        doc.setTitle(parsed.title());
        doc.setSourceFilename(parsed.sourceFilename());
        doc.setDocType(docType);
        doc.setFileFormat(parsed.format());
        doc.setSha256(parsed.sha256());
        doc.setContent(parsed.content());
        doc.setPageCount(parsed.pageCount());
        doc.setVersionNo(versionNo);
        doc.setParentDocumentId(parentDocumentId);
        doc.setStatus("PARSED");
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        documentMapper.insert(doc);

        int chunkCount = persistChunks(doc.getId(), parsed.sections(), now);
        return toUploadResponse(doc, chunkCount, duplicate);
    }

    private int persistChunks(Long documentId, List<ParsedSection> sections, LocalDateTime now) {
        int index = 0;
        for (ParsedSection section : sections) {
            List<TextChunk> pieces = textChunker.chunk(section.content());
            for (TextChunk piece : pieces) {
                ComplianceDocumentChunk row = new ComplianceDocumentChunk();
                row.setDocumentId(documentId);
                row.setChunkIndex(index++);
                row.setPageNo(section.pageNo());
                row.setSectionTitle(section.sectionTitle());
                row.setParagraphNo(section.paragraphNo());
                row.setContent(piece.content());
                row.setCharStart(section.charStart() + piece.charStart());
                row.setCharEnd(section.charStart() + piece.charEnd());
                row.setCreatedAt(now);
                chunkMapper.insert(row);
            }
        }
        return index;
    }

    private long countChunks(Long documentId) {
        return chunkMapper.selectCount(new QueryWrapper<ComplianceDocumentChunk>().eq("document_id", documentId));
    }

    private DocumentUploadResponse toUploadResponse(ComplianceDocument doc, long chunkCount, boolean duplicate) {
        return new DocumentUploadResponse(
                doc.getId(), doc.getTitle(), doc.getDocType(), doc.getFileFormat(), doc.getStatus(),
                doc.getContent() == null ? 0 : doc.getContent().length(), (int) chunkCount,
                value(doc.getPageCount()), value(doc.getVersionNo()), doc.getParentDocumentId(), duplicate,
                doc.getCreatedAt());
    }

    private DocumentListItem toListItem(ComplianceDocument doc) {
        return DocumentListItem.from(new DocumentListItem.ComplianceDocumentView(
                doc.getId(), doc.getTitle(), doc.getDocType(), doc.getFileFormat(), doc.getStatus(),
                doc.getContent() == null ? 0 : doc.getContent().length(), doc.getTenantId(),
                doc.getPageCount(), doc.getVersionNo(), doc.getParentDocumentId(), doc.getCreatedAt()));
    }

    private DocumentContentResponse toContentResponse(ComplianceDocument doc) {
        String content = doc.getContent() == null ? "" : doc.getContent();
        return new DocumentContentResponse(
                doc.getId(), doc.getTitle(), doc.getSourceFilename(), doc.getDocType(), doc.getFileFormat(),
                doc.getStatus(), content, content.length(), value(doc.getPageCount()), value(doc.getVersionNo()),
                doc.getParentDocumentId(), doc.getCreatedAt());
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static String normalizeDocType(String docType, String format) {
        if (StringUtils.hasText(docType)) {
            String normalized = docType.trim().toUpperCase();
            if (!List.of("CONTRACT", "POLICY", "PRIVACY", "DISCLOSURE", "GENERAL").contains(normalized)) {
                throw new BizException("文档类型必须是 CONTRACT/POLICY/PRIVACY/DISCLOSURE/GENERAL");
            }
            return normalized;
        }
        return "md".equals(format) ? "POLICY" : "GENERAL";
    }
}
