package com.portfolio.compliance.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("compliance_document")
public class ComplianceDocument {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String ownerId;
    private String title;
    private String sourceFilename;
    private String docType;
    private String fileFormat;
    private String sha256;
    private String content;
    private Integer pageCount;
    private Integer versionNo;
    private Long parentDocumentId;
    private String status;
    private String parseError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
