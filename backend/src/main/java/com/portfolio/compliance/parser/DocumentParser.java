package com.portfolio.compliance.parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.portfolio.compliance.common.BizException;
import com.portfolio.compliance.common.Hashing;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Component
public class DocumentParser {

    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("txt", "md", "markdown", "pdf", "docx");
    private static final Pattern HEADING = Pattern.compile(
            "^(?:#{1,6}\\s+.+|第[一二三四五六七八九十百千0-9]+[章节条].*|[0-9]+(?:\\.[0-9]+)*[、.．\\s].+)$");

    static {
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(10L * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(10L * 1024 * 1024);
    }

    public ParsedDocument parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("上传文件不能为空");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BizException(413, "文件大小不能超过 5MB");
        }
        try {
            return parseBytes(file.getBytes(), file.getOriginalFilename());
        } catch (IOException ex) {
            throw new BizException(400, "读取文件失败");
        }
    }

    public ParsedDocument parse(InputStream in, String fileName) {
        if (in == null) {
            throw new BizException("文件内容不能为空");
        }
        try {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new BizException(413, "文件大小不能超过 5MB");
            }
            return parseBytes(bytes, fileName);
        } catch (IOException ex) {
            throw new BizException(400, "读取文件失败");
        }
    }

    private ParsedDocument parseBytes(byte[] bytes, String fileName) {
        if (bytes.length == 0) {
            throw new BizException("上传文件不能为空");
        }
        String extension = extractExtension(fileName);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new BizException("暂仅支持 PDF / DOCX / TXT / Markdown 文件（.pdf / .docx / .txt / .md）");
        }
        validateSignature(bytes, extension);

        ParsedContent parsed = switch (extension) {
            case "pdf" -> parsePdf(bytes);
            case "docx" -> parseDocx(bytes);
            default -> parseText(bytes, "md".equals(extension) || "markdown".equals(extension));
        };
        if (!StringUtils.hasText(parsed.content())) {
            throw new BizException("文件内容为空，无法解析");
        }
        String normalizedFormat = "markdown".equals(extension) ? "md" : extension;
        return new ParsedDocument(
                deriveTitle(fileName),
                normalizedFormat,
                parsed.content(),
                parsed.pageCount(),
                List.copyOf(parsed.sections()),
                Hashing.sha256(bytes),
                sanitizeFilename(fileName));
    }

    private static ParsedContent parseText(byte[] bytes, boolean markdown) {
        String text = new String(stripUtf8Bom(bytes), StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .strip();
        if (text.indexOf('\0') >= 0 || binaryControlRatio(text) > 0.02d) {
            throw new BizException("文本文件包含二进制内容，已拒绝解析");
        }
        return new ParsedContent(text, 1, buildSections(text, 1, markdown));
    }

    private static ParsedContent parsePdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            StringBuilder content = new StringBuilder();
            List<ParsedSection> sections = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = normalizeExtractedText(stripper.getText(document));
                if (pageText.isBlank()) {
                    continue;
                }
                if (!content.isEmpty()) {
                    content.append('\n');
                }
                int base = content.length();
                content.append(pageText);
                sections.addAll(offsetSections(buildSections(pageText, page, false), base));
            }
            if (content.toString().isBlank()) {
                throw new BizException(
                        "扫描型 PDF 未检测到可提取文本；当前演示版未启用 OCR，请上传可复制文本的 PDF 或 DOCX/TXT/MD 文件");
            }
            return new ParsedContent(content.toString(), document.getNumberOfPages(), sections);
        } catch (InvalidPasswordException ex) {
            throw new BizException("加密 PDF 暂不支持，请解除密码后重试");
        } catch (BizException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BizException("PDF 解析失败，文件可能已损坏");
        }
    }

    private static ParsedContent parseDocx(byte[] bytes) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<String> blocks = new ArrayList<>();
            List<Boolean> headings = new ArrayList<>();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = normalizeExtractedText(paragraph.getText());
                if (!text.isBlank()) {
                    blocks.add(text);
                    String style = paragraph.getStyle();
                    headings.add((style != null && style.toLowerCase(Locale.ROOT).startsWith("heading"))
                            || HEADING.matcher(text).matches());
                }
            }
            for (XWPFTable table : document.getTables()) {
                table.getRows().forEach(row -> {
                    String text = row.getTableCells().stream()
                            .map(cell -> normalizeExtractedText(cell.getText()))
                            .filter(s -> !s.isBlank())
                            .reduce((a, b) -> a + "\t" + b)
                            .orElse("");
                    if (!text.isBlank()) {
                        blocks.add(text);
                        headings.add(false);
                    }
                });
            }
            String content = String.join("\n", blocks).strip();
            List<ParsedSection> sections = new ArrayList<>();
            String sectionTitle = null;
            int offset = 0;
            int paragraphNo = 0;
            for (int i = 0; i < blocks.size(); i++) {
                String block = blocks.get(i);
                if (headings.get(i)) {
                    sectionTitle = cleanHeading(block);
                }
                int start = content.indexOf(block, offset);
                int end = start + block.length();
                sections.add(new ParsedSection(null, sectionTitle, ++paragraphNo, start, end, block));
                offset = end;
            }
            return new ParsedContent(content, 0, sections);
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BizException("DOCX 解析失败，文件可能损坏或压缩结构不安全");
        }
    }

    private static List<ParsedSection> buildSections(String text, Integer pageNo, boolean markdown) {
        List<ParsedSection> sections = new ArrayList<>();
        String currentTitle = null;
        int paragraphNo = 0;
        int cursor = 0;
        for (String raw : text.split("\\n+")) {
            String block = raw.strip();
            if (block.isBlank()) {
                continue;
            }
            int start = text.indexOf(block, cursor);
            if (start < 0) {
                start = cursor;
            }
            int end = start + block.length();
            boolean heading = HEADING.matcher(block).matches() || (markdown && block.startsWith("#"));
            if (heading) {
                currentTitle = cleanHeading(block);
            }
            sections.add(new ParsedSection(pageNo, currentTitle, ++paragraphNo, start, end, block));
            cursor = end;
        }
        if (sections.isEmpty() && !text.isBlank()) {
            sections.add(new ParsedSection(pageNo, null, 1, 0, text.length(), text));
        }
        return sections;
    }

    private static List<ParsedSection> offsetSections(List<ParsedSection> sections, int offset) {
        return sections.stream()
                .map(s -> new ParsedSection(
                        s.pageNo(), s.sectionTitle(), s.paragraphNo(),
                        s.charStart() + offset, s.charEnd() + offset, s.content()))
                .toList();
    }

    private static void validateSignature(byte[] bytes, String extension) {
        if ("pdf".equals(extension) && !startsWith(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            throw new BizException("文件扩展名为 PDF，但内容不是有效 PDF");
        }
        if ("docx".equals(extension) && !(bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K')) {
            throw new BizException("文件扩展名为 DOCX，但内容不是有效 Office Open XML 文档");
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] stripUtf8Bom(byte[] bytes) {
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            return java.util.Arrays.copyOfRange(bytes, 3, bytes.length);
        }
        return bytes;
    }

    private static double binaryControlRatio(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        long controls = text.chars()
                .filter(c -> c < 32 && c != '\n' && c != '\t')
                .count();
        return (double) controls / text.length();
    }

    private static String normalizeExtractedText(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private static String cleanHeading(String value) {
        return value.replaceFirst("^#{1,6}\\s*", "").strip();
    }

    static String extractExtension(String fileName) {
        if (!StringUtils.hasText(fileName) || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    static String deriveTitle(String fileName) {
        String safe = sanitizeFilename(fileName);
        if (!StringUtils.hasText(safe)) {
            return "未命名文档";
        }
        int dot = safe.lastIndexOf('.');
        String base = dot > 0 ? safe.substring(0, dot) : safe;
        return base.isBlank() ? "未命名文档" : base;
    }

    static String sanitizeFilename(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "未命名文档";
        }
        String safe = fileName.replace('\0', '_');
        int slash = Math.max(safe.lastIndexOf('/'), safe.lastIndexOf('\\'));
        safe = slash >= 0 ? safe.substring(slash + 1) : safe;
        safe = safe.replaceAll("[\\r\\n]", "_").strip();
        return safe.isBlank() ? "未命名文档" : safe;
    }

    public record ParsedDocument(
            String title,
            String format,
            String content,
            int pageCount,
            List<ParsedSection> sections,
            String sha256,
            String sourceFilename) {
    }

    public record ParsedSection(
            Integer pageNo,
            String sectionTitle,
            int paragraphNo,
            int charStart,
            int charEnd,
            String content) {
    }

    private record ParsedContent(String content, int pageCount, List<ParsedSection> sections) {
    }
}
