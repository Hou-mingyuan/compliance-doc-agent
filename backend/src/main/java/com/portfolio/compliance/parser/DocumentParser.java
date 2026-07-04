package com.portfolio.compliance.parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import com.portfolio.compliance.common.BizException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Component
public class DocumentParser {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("txt", "md", "markdown");
    private static final int MAX_BYTES = 5 * 1024 * 1024;

    public ParsedDocument parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("上传文件不能为空");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BizException("文件大小不能超过 5MB");
        }

        String originalName = file.getOriginalFilename();
        String extension = extractExtension(originalName);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new BizException("暂仅支持 TXT / Markdown 文件（.txt / .md）");
        }

        try (InputStream in = file.getInputStream()) {
            String content = readUtf8(in);
            if (!StringUtils.hasText(content)) {
                throw new BizException("文件内容为空，无法解析");
            }
            String title = deriveTitle(originalName);
            return new ParsedDocument(title, extension, content.trim());
        } catch (IOException ex) {
            throw new BizException("读取文件失败：" + ex.getMessage());
        }
    }

    public ParsedDocument parse(InputStream in, String fileName) {
        String extension = extractExtension(fileName);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new BizException("暂仅支持 TXT / Markdown 文件（.txt / .md）");
        }
        try {
            String content = readUtf8(in);
            if (!StringUtils.hasText(content)) {
                throw new BizException("文件内容为空，无法解析");
            }
            return new ParsedDocument(deriveTitle(fileName), extension, content.trim());
        } catch (IOException ex) {
            throw new BizException("读取文件失败：" + ex.getMessage());
        }
    }

    private static String readUtf8(InputStream in) throws IOException {
        byte[] bytes = in.readAllBytes();
        if (bytes.length == 0) {
            return "";
        }
        Charset charset = detectCharset(bytes);
        return new String(bytes, charset);
    }

    private static Charset detectCharset(byte[] bytes) {
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            return StandardCharsets.UTF_8;
        }
        return StandardCharsets.UTF_8;
    }

    static String extractExtension(String fileName) {
        if (!StringUtils.hasText(fileName) || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    static String deriveTitle(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "未命名文档";
        }
        String base = fileName;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return base.isBlank() ? "未命名文档" : base;
    }

    public record ParsedDocument(String title, String format, String content) {
    }
}
