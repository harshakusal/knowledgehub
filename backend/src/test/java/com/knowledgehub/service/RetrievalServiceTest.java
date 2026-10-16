package com.knowledgehub.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.knowledgehub.client.LlmClient;
import com.knowledgehub.config.AppProperties;
import com.knowledgehub.exception.NotFoundException;
import com.knowledgehub.repository.ChunkRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RetrievalServiceTest {
    private final LlmClient llm = mock(LlmClient.class);
    private final ChunkRepository chunks = mock(ChunkRepository.class);
    private final DocumentPermissionService perms = mock(DocumentPermissionService.class);
    private final RetrievalService service = new RetrievalService(llm, chunks, perms,
            new AppProperties(null, new AppProperties.Rag(5, 500, 50), null));

    @Test
    void searchesOnlyTheCallersChunks() {
        float[] v = {1f};
        UUID d = UUID.randomUUID();
        when(perms.requireAllReadable(List.of(d), "alice")).thenReturn(List.of(d));
        when(llm.embed(List.of("q"))).thenReturn(List.of(v));
        service.retrieve("alice", "q", List.of(d));
        verify(chunks).searchSimilar("alice", List.of(d), v, 5);
    }

    @Test
    void foreignDocumentIdsAreRejectedBeforeEmbedding() {
        UUID d = UUID.randomUUID();
        when(perms.requireAllReadable(any(), eq("bob"))).thenThrow(new NotFoundException("Document not found"));
        assertThatThrownBy(() -> service.retrieve("bob", "q", List.of(d))).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(llm, chunks);
    }
}
