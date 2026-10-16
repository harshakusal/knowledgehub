package com.knowledgehub.service;

import com.knowledgehub.client.LlmClient;
import com.knowledgehub.model.Document;
import com.knowledgehub.model.DocumentStatus;
import com.knowledgehub.model.TextChunk;
import com.knowledgehub.repository.ChunkRepository;
import com.knowledgehub.repository.DocumentRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Separate bean so the @Async proxy is used (no self-invocation). Chunk -> embed -> store in pgvector. */
@Service
public class IngestionWorker {
    private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);
    private static final int BATCH = 32;

    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final ChunkingService chunker;
    private final LlmClient llm;

    public IngestionWorker(DocumentRepository documents, ChunkRepository chunks, ChunkingService chunker, LlmClient llm) {
        this.documents = documents;
        this.chunks = chunks;
        this.chunker = chunker;
        this.llm = llm;
    }

    @Async("llmExecutor")
    public CompletableFuture<DocumentStatus> ingest(UUID documentId) {
        Document doc = documents.findById(documentId).orElse(null);
        if (doc == null) {
            return CompletableFuture.completedFuture(DocumentStatus.FAILED);
        }
        try {
            List<TextChunk> parts = chunker.chunk(doc.content());
            List<float[]> vectors = new ArrayList<>();
            for (int i = 0; i < parts.size(); i += BATCH) {
                List<String> batch = parts.subList(i, Math.min(parts.size(), i + BATCH)).stream().map(TextChunk::text).toList();
                vectors.addAll(llm.embed(batch));
            }
            chunks.replaceChunks(doc, parts, vectors);
            documents.updateStatus(documentId, DocumentStatus.READY);
            log.info("Ingested document {} into {} chunks", documentId, parts.size());
            return CompletableFuture.completedFuture(DocumentStatus.READY);
        } catch (RuntimeException e) {
            log.warn("Ingestion failed for document {}: {}", documentId, e.getMessage());
            documents.updateStatus(documentId, DocumentStatus.FAILED);
            return CompletableFuture.completedFuture(DocumentStatus.FAILED);
        }
    }
}
