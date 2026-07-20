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
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
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
    void parsesDocxAndKeepsParagraphLocation() throws Exception {
        byte[] bytes;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("第一条 适用范围");
            document.createParagraph().createRun().setText("甲方：演示科技有限公司");
            document.write(out);
            bytes = out.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes);

        DocumentParser.ParsedDocument doc = parser.parse(file);

        assertThat(doc.format()).isEqualTo("docx");
        assertThat(doc.content()).contains("演示科技有限公司");
        assertThat(doc.sections()).hasSize(2);
        assertThat(doc.sections().get(1).paragraphNo()).isEqualTo(2);
        assertThat(doc.sha256()).hasSize(64);
    }

    @Test
    void rejectsUnsupportedExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.xlsx", "application/vnd.openxmlformats", "binary".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("PDF / DOCX / TXT / Markdown");
    }

    @Test
    void rejectsMismatchedPdfSignature() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.pdf", "application/pdf", "not-a-pdf".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不是有效 PDF");
    }

    @Test
    void rejectsScannedPdfWithoutOcrClaim() throws Exception {
        byte[] bytes;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(out);
            bytes = out.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile("file", "scan.pdf", "application/pdf", bytes);

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未启用 OCR");
    }

    @Test
    void extractsPlainTextFromPdf() throws Exception {
        byte[] pdfBytes = samplePdf("Data protection compliance clause for both parties.");
        MockMultipartFile file = new MockMultipartFile(
                "file", "合规条款.pdf", "application/pdf", pdfBytes);

        DocumentParser.ParsedDocument doc = parser.parse(file);

        assertThat(doc.format()).isEqualTo("pdf");
        assertThat(doc.content()).contains("Data protection");
        assertThat(doc.pageCount()).isEqualTo(1);
        assertThat(doc.sections()).allMatch(section -> Integer.valueOf(1).equals(section.pageNo()));
    }

    @Test
    void rejectsFilesLargerThanFiveMegabytes() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "oversized.txt", "text/plain", new byte[DocumentParser.MAX_BYTES + 1]);

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOfSatisfying(BizException.class, ex -> assertThat(ex.getCode()).isEqualTo(413))
                .hasMessageContaining("5MB");
    }

    @Test
    void rejectsBinaryPayloadDisguisedAsText() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "payload.txt", "text/plain", new byte[] { 'D', 'E', 'M', 'O', 0, 1, 2, 3 });

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("二进制内容");
    }

    @Test
    void rejectsFakeAndMalformedDocxPayloads() {
        MockMultipartFile fake = new MockMultipartFile(
                "file", "fake.docx", "application/octet-stream", "not-zip".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile malformed = new MockMultipartFile(
                "file", "malformed.docx", "application/octet-stream", new byte[] { 'P', 'K', 3, 4, 0, 0 });

        assertThatThrownBy(() -> parser.parse(fake))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不是有效 Office Open XML");
        assertThatThrownBy(() -> parser.parse(malformed))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("损坏或压缩结构不安全");
    }

    @Test
    void rejectsEncryptedPdfWithActionableMessage() throws Exception {
        byte[] bytes;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            StandardProtectionPolicy policy = new StandardProtectionPolicy(
                    "owner-demo", "user-demo", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            document.save(out);
            bytes = out.toByteArray();
        }
        MockMultipartFile file = new MockMultipartFile("file", "encrypted.pdf", "application/pdf", bytes);

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("加密 PDF");
    }

    @Test
    void stripsPathComponentsAndControlCharactersFromFilename() {
        DocumentParser.ParsedDocument doc = parser.parse(
                new ByteArrayInputStream("DEMO".getBytes(StandardCharsets.UTF_8)),
                "C:\\private\\..\\演示\r\n合同.txt");

        assertThat(doc.sourceFilename()).isEqualTo("演示__合同.txt");
        assertThat(doc.title()).isEqualTo("演示__合同");
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
