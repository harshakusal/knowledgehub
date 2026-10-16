package com.knowledgehub.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.knowledgehub.client.LlmClient;
import com.knowledgehub.exception.LlmUnavailableException;
import com.knowledgehub.model.RetrievedChunk;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ChatStreamServiceTest {
    private final RetrievalService retrieval = mock(RetrievalService.class);
    private final LlmClient llm = mock(LlmClient.class);
    private final ChatStreamService service = new ChatStreamService(retrieval, new PromptBuilder(), llm, Runnable::run);

    /** Captures event names; optionally fails after N sends to simulate the browser disconnecting. */
    static class RecordingEmitter extends SseEmitter {
        final List<String> events = new ArrayList<>();
        final int failAfter;
        boolean completed;

        RecordingEmitter(int failAfter) {
            this.failAfter = failAfter;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (events.size() >= failAfter) {
                throw new IOException("Broken pipe");
            }
            Set<DataWithMediaType> parts = builder.build();
            String text = parts.stream().map(p -> String.valueOf(p.getData())).reduce("", String::concat);
            events.add(text.substring(text.indexOf("event:") + 6, text.indexOf('\n', text.indexOf("event:"))));
        }

        @Override
        public void complete() {
            completed = true;
        }
    }

    @SuppressWarnings("unchecked")
    private void llmStreams(String... tokens) {
        doAnswer(inv -> {
            Consumer<String> sink = inv.getArgument(1);
            BooleanSupplier cancelled = inv.getArgument(2);
            for (String t : tokens) {
                if (cancelled.getAsBoolean()) {
                    return null;
                }
                sink.accept(t);
            }
            return null;
        }).when(llm).streamChat(any(), any(Consumer.class), any(BooleanSupplier.class));
    }

    private void someContext() {
        when(retrieval.retrieve(any(), any(), any()))
                .thenReturn(List.of(new RetrievedChunk(UUID.randomUUID(), 0, "t", null, "c", 0.1)));
    }

    @Test
    void streamsSourcesTokensThenDone() {
        someContext();
        llmStreams("a", "b", "c");
        RecordingEmitter e = new RecordingEmitter(100);
        service.start("alice", "q", List.of(), e, new AtomicBoolean());
        assertThat(e.events).containsExactly("sources", "token", "token", "token", "done");
        assertThat(e.completed).isTrue();
    }

    @Test
    void stopsCallingLlmWhenClientDisconnects() {
        someContext();
        llmStreams("a", "b", "c", "d", "e");
        RecordingEmitter e = new RecordingEmitter(3); // sources + 2 tokens then broken pipe
        AtomicBoolean cancelled = new AtomicBoolean();
        service.start("alice", "q", List.of(), e, cancelled);
        assertThat(cancelled).isTrue();
        assertThat(e.events).containsExactly("sources", "token", "token");
        assertThat(e.completed).isFalse();
    }

    @Test
    void llmFailureBecomesErrorEvent() {
        someContext();
        doThrow(new LlmUnavailableException("busy", null)).when(llm).streamChat(any(), any(), any());
        RecordingEmitter e = new RecordingEmitter(100);
        service.start("alice", "q", List.of(), e, new AtomicBoolean());
        assertThat(e.events).containsExactly("sources", "error");
        assertThat(e.completed).isTrue();
    }

    @Test
    void noContextSkipsLlm() {
        when(retrieval.retrieve(any(), any(), any())).thenReturn(List.of());
        RecordingEmitter e = new RecordingEmitter(100);
        service.start("alice", "q", List.of(), e, new AtomicBoolean());
        assertThat(e.events).containsExactly("sources", "token", "done");
        verifyNoInteractions(llm);
    }

    @Test
    void dataIsJson() {
        assertThat(SseEmitter.event().name("token").data("x", MediaType.APPLICATION_JSON).build()).isNotEmpty();
    }
}
