package com.knowledgehub.service;

import com.knowledgehub.config.UserContext;
import com.knowledgehub.dto.RagAnswer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Runs RAG answers on llmExecutor so the servlet thread is released while the LLM thinks. */
@Service
public class RagWorker {
    private final RagService rag;

    public RagWorker(RagService rag) {
        this.rag = rag;
    }

    @Async("llmExecutor")
    public CompletableFuture<RagAnswer> answer(String question, List<UUID> documentIds) {
        // user id comes from the propagated context (UserContextTaskDecorator)
        return CompletableFuture.completedFuture(rag.answer(UserContext.require(), question, documentIds));
    }
}
