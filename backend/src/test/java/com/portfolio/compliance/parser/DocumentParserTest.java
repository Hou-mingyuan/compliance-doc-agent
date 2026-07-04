package com.portfolio.compliance.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import com.portfolio.compliance.common.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void parsesPlainTextFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "合同条款.txt",
                "text/plain",
                "第一条 双方约定争议解决方式。".getBytes(StandardCharsets.UTF_8));

        DocumentParser.ParsedDocument doc = parser.parse(file);

        assertThat(doc.title()).isEqualTo("合同条款");
        assertThat(doc.format()).isEqualTo("txt");
        assertThat(doc.content()).contains("争议解决");
    }

    @Test
    void parsesMarkdownFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "采购管理制度.md",
                "text/markdown",
                "# 采购管理制度\n\n## 适用范围\n本制度适用于全集团。".getBytes(StandardCharsets.UTF_8));

        DocumentParser.ParsedDocument doc = parser.parse(file);

        assertThat(doc.title()).isEqualTo("采购管理制度");
        assertThat(doc.format()).isEqualTo("md");
        assertThat(doc.content()).contains("适用范围");
    }

    @Test
    void parsesFromInputStreamWithUnicodeFileName() {
        byte[] bytes = "保密条款内容".getBytes(StandardCharsets.UTF_8);
        DocumentParser.ParsedDocument doc = parser.parse(
                new ByteArrayInputStream(bytes), "docs/保密协议 2026.md");

        assertThat(doc.title()).isEqualTo("保密协议 2026");
        assertThat(doc.content()).isEqualTo("保密条款内容");
    }

    @Test
    void rejectsEmptyFile() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    void rejectsBlankContent() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "blank.txt", "text/plain", "   \n  ".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("内容为空");
    }

    @Test
    void rejectsUnsupportedExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", "binary".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("TXT / Markdown");
    }

    @Test
    @org.junit.jupiter.api.Disabled("TODO: 实现 DocumentParser 后 — 从 classpath sample.docx 提取纯文本")
    void extractsPlainTextFromDocx() {
    }

    @Test
    @org.junit.jupiter.api.Disabled("TODO: 实现 DocumentParser 后 — 从 classpath sample.pdf 提取文本")
    void extractsPlainTextFromPdf() {
    }
}
