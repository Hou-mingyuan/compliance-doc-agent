package com.portfolio.compliance.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import com.portfolio.compliance.ComplianceDocAgentApplication;
import com.portfolio.compliance.mapper.ComplianceDocumentChunkMapper;
import com.portfolio.compliance.mapper.ComplianceDocumentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = ComplianceDocAgentApplication.class)
@AutoConfigureMockMvc
@Transactional
class DocumentUploadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ComplianceDocumentMapper documentMapper;

    @Autowired
    private ComplianceDocumentChunkMapper chunkMapper;

    @Test
    void uploadTxtPersistsDocument() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "合同条款片段.txt",
                "text/plain",
                "甲方与乙方就技术服务达成协议。".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/documents/upload").file(file).param("docType", "CONTRACT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.title").value("合同条款片段"))
                .andExpect(jsonPath("$.data.docType").value("CONTRACT"))
                .andExpect(jsonPath("$.data.format").value("txt"))
                .andExpect(jsonPath("$.data.status").value("UPLOADED"))
                .andExpect(jsonPath("$.data.contentLength").value(15))
                .andExpect(jsonPath("$.data.chunkCount").value(1));

        assertThat(documentMapper.selectCount(null)).isEqualTo(1);
        assertThat(chunkMapper.selectCount(null)).isEqualTo(1);
    }

    @Test
    void uploadMdDefaultsDocTypeToPolicy() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "内控制度.md",
                "text/markdown",
                "# 内控制度\n\n## 总则".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/documents/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.docType").value("POLICY"))
                .andExpect(jsonPath("$.data.format").value("md"));
    }

    @Test
    void uploadRejectsUnsupportedFormat() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "scan.docx", "application/vnd.openxmlformats", "binary".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/documents/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").isString());
    }
}
