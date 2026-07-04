package com.portfolio.compliance.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("compliance_check")
public class ComplianceCheck {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long documentId;
    private String ruleCode;
    private String severity;
    private String message;
    private LocalDateTime createdAt;
}
