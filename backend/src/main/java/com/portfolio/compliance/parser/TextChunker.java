package com.portfolio.compliance.parser;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/** 中文友好的递归字符分块（段落 → 句末标点 → 空格 → 硬切）。 */
@Component
public class TextChunker {

    private static final String[] SEPARATORS = {
            "\n\n", "\n", "。", "！", "？", "；", ". ", "! ", "? ", "; ", "，", " ", ""
    };

    private final int chunkSize;
    private final int chunkOverlap;

    public TextChunker() {
        this(800, 120);
    }

    public TextChunker(int chunkSize, int chunkOverlap) {
        this.chunkSize = chunkSize;
        this.chunkOverlap = Math.min(chunkOverlap, chunkSize / 2);
    }

    public List<TextChunk> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<TextChunk> chunks = new ArrayList<>();
        splitRecursive(text.trim(), 0, chunks);
        for (int i = 0; i < chunks.size(); i++) {
            chunks.set(i, new TextChunk(i, chunks.get(i).content(), chunks.get(i).charStart(), chunks.get(i).charEnd()));
        }
        return chunks;
    }

    private void splitRecursive(String text, int baseOffset, List<TextChunk> out) {
        if (text.length() <= chunkSize) {
            if (!text.isBlank()) {
                out.add(new TextChunk(out.size(), text, baseOffset, baseOffset + text.length()));
            }
            return;
        }

        int splitAt = findSplit(text);
        String head = text.substring(0, splitAt).trim();
        String tail = text.substring(splitAt).trim();

        if (!head.isBlank()) {
            out.add(new TextChunk(out.size(), head, baseOffset, baseOffset + splitAt));
        }

        if (tail.isBlank()) {
            return;
        }

        if (chunkOverlap > 0 && !head.isBlank()) {
            int overlapStart = Math.max(0, head.length() - chunkOverlap);
            String overlap = head.substring(overlapStart);
            tail = overlap + tail;
            baseOffset = baseOffset + splitAt - overlap.length();
        } else {
            baseOffset = baseOffset + splitAt;
        }

        splitRecursive(tail, baseOffset, out);
    }

    private int findSplit(String text) {
        for (String sep : SEPARATORS) {
            if (sep.isEmpty()) {
                return chunkSize;
            }
            int searchFrom = Math.min(text.length(), chunkSize);
            int idx = text.lastIndexOf(sep, searchFrom);
            if (idx > chunkSize / 4) {
                return idx + sep.length();
            }
        }
        return chunkSize;
    }

    public record TextChunk(int index, String content, int charStart, int charEnd) {
    }
}
