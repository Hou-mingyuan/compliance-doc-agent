package com.portfolio.compliance.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import com.portfolio.compliance.common.BizException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
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
                "file", "report.docx", "application/vnd.openxmlformats", "binary".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("PDF / TXT / Markdown");
    }

    @Test
    void extractsPlainTextFromPdf() throws Exception {
        byte[] pdfBytes = samplePdf("Data protection compliance clause for both parties.");
        MockMultipartFile file = new MockMultipartFile(
                "file", "合规条款.pdf", "application/pdf", pdfBytes);

        DocumentParser.ParsedDocument doc = parser.parse(file);

        assertThat(doc.format()).isEqualTo("pdf");
        assertThat(doc.content()).contains("Data protection");
    }

    private static byte[] samplePdf(String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                cs.beginText();
                cs.setFont(font, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
