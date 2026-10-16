package com.knowledgehub.service;

import com.knowledgehub.client.LlmClient;
import com.knowledgehub.config.UserContext;
import com.knowledgehub.model.Document;
import com.knowledgehub.model.JobStatus;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Separate bean from SummaryService so @Async goes through the proxy (self-invocation would run synchronously). */
@Service
public class SummaryWorker {
    private static final int MAX_CHARS = 12_000;
    private final DocumentPermissionService permissions;
    private final JobService jobs;
    private final PromptBuilder prompts;
    private final LlmClient llm;

    public SummaryWorker(DocumentPermissionService permissions, JobService jobs, PromptBuilder prompts, LlmClient llm) {
        this.permissions = permissions;
        this.jobs = jobs;
        this.prompts = prompts;
        this.llm = llm;
    }

    @Async("llmExecutor")
    public CompletableFuture<String> summarize(UUID jobId, UUID documentId) {
        jobs.update(jobId, JobStatus.RUNNING, null, null);
        try {
            // Re-check permission on the worker thread using the propagated user context.
            Document doc = permissions.requireReadable(documentId, UserContext.require());
            String text = doc.content().length() > MAX_CHARS ? doc.content().substring(0, MAX_CHARS) : doc.content();
            String summary = llm.complete(prompts.summaryPrompt(doc.title(), text), false);
            jobs.update(jobId, JobStatus.SUCCEEDED, summary, null);
            return CompletableFuture.completedFuture(summary);
        } catch (RuntimeException e) {
            jobs.update(jobId, JobStatus.FAILED, null, e.getMessage());
            return CompletableFuture.failedFuture(e);
        }
    }
}
