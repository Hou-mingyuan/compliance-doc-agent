package com.portfolio.compliance.parser;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextChunkerTest {

    private final TextChunker chunker = new TextChunker(20, 5);

    @Test
    void splitsLongTextIntoMultipleChunks() {
        String text = "第一条 双方约定争议解决方式。第二条 保密义务持续有效。第三条 违约责任。";
        var chunks = chunker.chunk(text);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks.get(0).index()).isZero();
        assertThat(String.join("", chunks.stream().map(TextChunker.TextChunk::content).toList()))
                .contains("争议解决");
    }

    @Test
    void keepsShortTextAsSingleChunk() {
        var chunks = chunker.chunk("短文本。");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).isEqualTo("短文本。");
    }
}
