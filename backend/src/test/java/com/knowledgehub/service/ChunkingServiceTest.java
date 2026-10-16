package com.knowledgehub.service;

import static org.assertj.core.api.Assertions.*;

import com.knowledgehub.model.TextChunk;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ChunkingServiceTest {
    private static ChunkingService chunker(int size, int overlap) {
        return new ChunkingService(new com.knowledgehub.config.AppProperties(null,
                new com.knowledgehub.config.AppProperties.Rag(5, size, overlap), null));
    }

    @Test
    void splitsWithOverlapAndIndexes() {
        String text = IntStream.range(0, 1200).mapToObj(i -> "w" + i).collect(Collectors.joining(" "));
        List<TextChunk> chunks = chunker(500, 50).chunk(text);
        assertThat(chunks).hasSize(3);
        assertThat(chunks).extracting(TextChunk::chunkIndex).containsExactly(0, 1, 2);
        assertThat(chunks.get(0).text()).startsWith("w0 ").endsWith(" w499");
        assertThat(chunks.get(1).text()).startsWith("w450 ").endsWith(" w949");
        assertThat(chunks.get(2).text()).startsWith("w900 ").endsWith(" w1199");
    }

    @Test
    void tracksSectionHeadings() {
        String text = "# Leave\n" + "a ".repeat(10) + "\n## Expenses\n" + "b ".repeat(10);
        List<TextChunk> chunks = chunker(8, 2).chunk(text);
        assertThat(chunks.get(0).sectionHeading()).isEqualTo("Leave");
        assertThat(chunks.get(chunks.size() - 1).sectionHeading()).isEqualTo("Expenses");
    }

    @Test
    void shortTextIsOneChunkAndEmptyIsNone() {
        assertThat(chunker(500, 50).chunk("hello world")).hasSize(1);
        assertThat(chunker(500, 50).chunk("  ")).isEmpty();
    }

    @Test
    void rejectsInvalidConfig() {
        assertThatThrownBy(() -> chunker(10, 10)).isInstanceOf(IllegalArgumentException.class);
    }
}
