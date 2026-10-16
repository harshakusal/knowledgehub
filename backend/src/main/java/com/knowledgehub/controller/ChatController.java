package com.knowledgehub.controller;

import com.knowledgehub.config.AppProperties;
import com.knowledgehub.config.UserContext;
import com.knowledgehub.dto.ChatRequest;
import com.knowledgehub.dto.RagAnswer;
import com.knowledgehub.service.ChatStreamService;
import com.knowledgehub.service.DocumentPermissionService;
import com.knowledgehub.service.RagWorker;
import jakarta.validation.Valid;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private final ChatStreamService streams;
    private final RagWorker rag;
    private final DocumentPermissionService permissions;
    private final AppProperties props;

    public ChatController(ChatStreamService streams, RagWorker rag, DocumentPermissionService permissions, AppProperties props) {
        this.streams = streams;
        this.rag = rag;
        this.permissions = permissions;
        this.props = props;
    }

    /** Streams tokens as text/event-stream. Web thread returns immediately; work runs on llmExecutor. */
    @PostMapping(path = "/stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        String userId = UserContext.require();
        // fail fast with a real 404 before the stream opens if any requested document isn't the user's
        permissions.requireAllReadable(request.documentIdsOrEmpty(), userId);
        SseEmitter emitter = new SseEmitter(props.sseTimeout().toMillis());
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> {
            cancelled.set(true);
            emitter.complete();
        });
        emitter.onError(e -> cancelled.set(true));
        streams.start(userId, request.question(), request.documentIdsOrEmpty(), emitter, cancelled);
        return emitter;
    }

    /** Structured (validated JSON) answer with citations. Async so the servlet thread is freed. */
    @PostMapping(path = "/answer", consumes = MediaType.APPLICATION_JSON_VALUE)
    public CompletableFuture<RagAnswer> answer(@Valid @RequestBody ChatRequest request) {
        return rag.answer(request.question(), request.documentIdsOrEmpty());
    }
}
