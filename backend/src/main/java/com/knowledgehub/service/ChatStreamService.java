package com.knowledgehub.service;

import com.knowledgehub.client.LlmClient;
import com.knowledgehub.dto.Citation;
import com.knowledgehub.model.RetrievedChunk;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Streams an answer to the browser as SSE. Events:
 * <pre>
 * event: sources  data: [{"documentId":..,"chunkIndex":..}]
 * event: token    data: {"t":"..."}        (JSON so spaces/newlines survive SSE framing)
 * event: error    data: {"message":"..."}
 * event: done     data: {}
 * </pre>
 * When the client disconnects (Stop / tab closed) {@code cancelled} flips and we stop reading from the LLM.
 */
@Service
public class ChatStreamService {
    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);
    private final RetrievalService retrieval;
    private final PromptBuilder prompts;
    private final LlmClient llm;
    private final TaskExecutor executor;

    public ChatStreamService(RetrievalService retrieval, PromptBuilder prompts, LlmClient llm,
                             @Qualifier("llmExecutor") TaskExecutor executor) {
        this.retrieval = retrieval;
        this.prompts = prompts;
        this.llm = llm;
        this.executor = executor;
    }

    /** Validates access synchronously (so 404s are real HTTP errors) then streams on llmExecutor. */
    public void start(String userId, String question, List<UUID> documentIds, SseEmitter emitter, AtomicBoolean cancelled) {
        executor.execute(() -> stream(userId, question, documentIds, emitter, cancelled));
    }

    void stream(String userId, String question, List<UUID> documentIds, SseEmitter emitter, AtomicBoolean cancelled) {
        try {
            List<RetrievedChunk> context = retrieval.retrieve(userId, question, documentIds);
            send(emitter, cancelled, "sources", context.stream().map(c -> new Citation(c.documentId(), c.chunkIndex())).toList());
            if (context.isEmpty()) {
                send(emitter, cancelled, "token", Map.of("t", "I couldn't find anything relevant in your documents."));
            } else {
                llm.streamChat(prompts.streamingPrompt(question, context),
                        token -> send(emitter, cancelled, "token", Map.of("t", token)),
                        cancelled::get);
            }
            if (!cancelled.get()) {
                send(emitter, cancelled, "done", Map.of());
                emitter.complete();
            }
        } catch (ClientGoneException e) {
            log.info("Client disconnected; stream stopped");
        } catch (RuntimeException e) {
            log.warn("Chat stream failed: {}", e.getMessage());
            try {
                send(emitter, cancelled, "error", Map.of("message", userMessage(e)));
                emitter.complete();
            } catch (ClientGoneException ignored) {
                // nothing to tell
            }
        }
    }

    private static String userMessage(RuntimeException e) {
        if (e instanceof com.knowledgehub.exception.LlmUnavailableException || e instanceof com.knowledgehub.exception.NotFoundException) {
            return e.getMessage();
        }
        return "Something went wrong while generating the answer.";
    }

    private void send(SseEmitter emitter, AtomicBoolean cancelled, String event, Object data) {
        if (cancelled.get()) {
            throw new ClientGoneException();
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            cancelled.set(true);
            throw new ClientGoneException();
        }
    }

    static final class ClientGoneException extends RuntimeException {
        ClientGoneException() {
            super(null, null, false, false);
        }
    }
}
