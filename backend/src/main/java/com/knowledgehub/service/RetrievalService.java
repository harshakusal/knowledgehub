package com.knowledgehub.service;

import com.knowledgehub.client.LlmClient;
import com.knowledgehub.config.AppProperties;
import com.knowledgehub.model.RetrievedChunk;
import com.knowledgehub.repository.ChunkRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RetrievalService {
    private final LlmClient llm;
    private final ChunkRepository chunks;
    private final DocumentPermissionService permissions;
    private final int topK;

    public RetrievalService(LlmClient llm, ChunkRepository chunks, DocumentPermissionService permissions, AppProperties props) {
        this.llm = llm;
        this.chunks = chunks;
        this.permissions = permissions;
        this.topK = props.rag().topK();
    }

    /** Top-k similar chunks from the user's OWN documents (optionally limited to documentIds they own). */
    public List<RetrievedChunk> retrieve(String userId, String question, List<UUID> documentIds) {
        List<UUID> scope = permissions.requireAllReadable(documentIds, userId);
        float[] q = llm.embed(List.of(question)).get(0);
        return chunks.searchSimilar(userId, scope, q, topK);
    }
}
