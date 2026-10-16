package com.knowledgehub.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.knowledgehub.client.LlmClient;
import com.knowledgehub.dto.Confidence;
import com.knowledgehub.dto.RagAnswer;
import com.knowledgehub.model.RetrievedChunk;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RagServiceTest {
    private final RetrievalService retrieval = mock(RetrievalService.class);
    private final LlmClient llm = mock(LlmClient.class);
    private final RagService rag = new RagService(retrieval, new PromptBuilder(), llm);
    private final UUID doc = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(retrieval.retrieve(eq("alice"), any(), any()))
                .thenReturn(List.of(new RetrievedChunk(doc, 0, "Leave", null, "20 days leave", 0.1)));
    }

    private String json(int chunk, boolean found) {
        return "{\"answer\":\"20 days\",\"citations\":[{\"documentId\":\"" + doc + "\",\"chunkIndex\":" + chunk
                + "}],\"confidence\":\"high\",\"answerFound\":" + found + "}";
    }

    @Test
    void validAnswerIsReturned() {
        when(llm.complete(any(), eq(true))).thenReturn("```json\n" + json(0, true) + "\n```");
        RagAnswer a = rag.answer("alice", "leave?", List.of());
        assertThat(a.answerFound()).isTrue();
        assertThat(a.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(a.citations()).singleElement().satisfies(c -> assertThat(c.documentId()).isEqualTo(doc));
        verify(llm, times(1)).complete(any(), eq(true));
    }

    @Test
    void invalidThenValidRetriesOnce() {
        when(llm.complete(any(), eq(true))).thenReturn("not json", json(0, true));
        assertThat(rag.answer("alice", "q", List.of()).answerFound()).isTrue();
        verify(llm, times(2)).complete(any(), eq(true));
    }

    @Test
    void hallucinatedCitationTwiceFallsBackSafely() {
        when(llm.complete(any(), eq(true))).thenReturn(json(99, true));
        RagAnswer a = rag.answer("alice", "q", List.of());
        assertThat(a).isEqualTo(RagAnswer.notFound());
        verify(llm, times(2)).complete(any(), eq(true));
    }

    @Test
    void foundWithoutCitationsIsInvalid() {
        String noCites = "{\"answer\":\"x\",\"citations\":[],\"confidence\":\"low\",\"answerFound\":true}";
        assertThat(rag.validate(noCites, List.of())).isEmpty();
        String notFound = "{\"answer\":\"not in docs\",\"citations\":[],\"confidence\":\"low\",\"answerFound\":false}";
        assertThat(rag.validate(notFound, List.of())).isPresent();
        assertThat(rag.validate("{\"answer\":\"x\",\"citations\":[],\"confidence\":\"certain\",\"answerFound\":false}", List.of())).isEmpty();
    }

    @Test
    void noRetrievedChunksMeansNotFoundWithoutCallingLlm() {
        when(retrieval.retrieve(eq("bob"), any(), any())).thenReturn(List.of());
        assertThat(rag.answer("bob", "q", List.of()).answerFound()).isFalse();
        verifyNoInteractions(llm);
    }
}
