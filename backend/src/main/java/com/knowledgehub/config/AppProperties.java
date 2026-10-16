package com.knowledgehub.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Executor executor, Rag rag, Duration sseTimeout) {
    public record Executor(int coreSize, int maxSize, int queueCapacity) {
    }

    public record Rag(int topK, int chunkSize, int chunkOverlap) {
    }
}
