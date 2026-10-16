package com.knowledgehub.service;

import com.knowledgehub.config.AppProperties;
import com.knowledgehub.model.TextChunk;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Splits text into ~chunkSize-token windows with chunkOverlap tokens of overlap. "Token" is approximated by a
 * whitespace-delimited word (good enough for English policy docs; a real tokenizer can be swapped in).
 * Markdown headings (# ...) become the sectionHeading of the chunks that start under them.
 */
@Service
public class ChunkingService {
    private final int size;
    private final int overlap;

    public ChunkingService(AppProperties props) {
        int size = props.rag().chunkSize();
        int overlap = props.rag().chunkOverlap();
        if (size <= 0 || overlap < 0 || overlap >= size) {
            throw new IllegalArgumentException("Invalid chunk size/overlap");
        }
        this.size = size;
        this.overlap = overlap;
    }

    private record Word(String text, String heading) {
    }

    public List<TextChunk> chunk(String text) {
        List<Word> words = new ArrayList<>();
        String heading = null;
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#")) {
                heading = trimmed.replaceFirst("^#+\\s*", "");
                if (heading.length() > 255) {
                    heading = heading.substring(0, 255);
                }
            }
            for (String w : trimmed.split("\\s+")) {
                if (!w.isEmpty()) {
                    words.add(new Word(w, heading));
                }
            }
        }
        List<TextChunk> chunks = new ArrayList<>();
        int step = size - overlap;
        for (int start = 0; start < words.size(); start += step) {
            int end = Math.min(words.size(), start + size);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                if (i > start) {
                    sb.append(' ');
                }
                sb.append(words.get(i).text());
            }
            chunks.add(new TextChunk(chunks.size(), words.get(start).heading(), sb.toString()));
            if (end == words.size()) {
                break;
            }
        }
        return chunks;
    }
}
