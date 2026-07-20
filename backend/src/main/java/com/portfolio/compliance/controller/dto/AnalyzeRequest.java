package com.portfolio.compliance.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AnalyzeRequest {

    @NotBlank(message = "文档标题不能为空")
    @Size(max = 200, message = "文档标题不能超过 200 字")
    private String title;

    @NotBlank(message = "文档内容不能为空")
    private String content;

    @Pattern(regexp = "(?i)CONTRACT|POLICY|PRIVACY|DISCLOSURE|GENERAL",
            message = "文档类型必须是 CONTRACT/POLICY/PRIVACY/DISCLOSURE/GENERAL")
    private String docType = "GENERAL";
}
