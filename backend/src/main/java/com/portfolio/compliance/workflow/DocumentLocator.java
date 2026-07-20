package com.portfolio.compliance.workflow;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.portfolio.compliance.entity.ComplianceDocumentChunk;
import com.portfolio.compliance.mapper.ComplianceDocumentChunkMapper;
import org.springframework.stereotype.Component;

@Component
public class DocumentLocator {

    private final ComplianceDocumentChunkMapper chunks;

    public DocumentLocator(ComplianceDocumentChunkMapper chunks) {
        this.chunks = chunks;
    }

    public Location locate(Long documentId, Integer start, Integer end) {
        if (documentId == null || start == null || start < 0) {
            return Location.empty();
        }
        ComplianceDocumentChunk chunk = chunks.selectOne(new QueryWrapper<ComplianceDocumentChunk>()
                .eq("document_id", documentId)
                .le("char_start", start)
                .gt("char_end", start)
                .orderByAsc("chunk_index")
                .last("LIMIT 1"));
        if (chunk == null) {
            chunk = chunks.selectOne(new QueryWrapper<ComplianceDocumentChunk>()
                    .eq("document_id", documentId)
                    .orderByAsc("chunk_index")
                    .last("LIMIT 1"));
        }
        return chunk == null
                ? Location.empty()
                : new Location(
                        chunk.getId(), chunk.getPageNo(), chunk.getSectionTitle(), chunk.getParagraphNo(),
                        start, end, chunk.getContent());
    }

    public record Location(
            Long chunkId,
            Integer pageNo,
            String sectionTitle,
            Integer paragraphNo,
            Integer matchStart,
            Integer matchEnd,
            String chunkText) {

        public static Location empty() {
            return new Location(null, null, null, null, null, null, null);
        }
    }
}
