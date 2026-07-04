package com.portfolio.compliance.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("compliance_document_chunk")
public class ComplianceDocumentChunk {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long documentId;
    private Integer chunkIndex;
    private String content;
    private Integer charStart;
    private Integer charEnd;
    private LocalDateTime createdAt;
}
