package com.knowledgehub.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.knowledgehub.client.LlmClient;
import com.knowledgehub.config.UserContext;
import com.knowledgehub.dto.JobResponse;
import com.knowledgehub.exception.NotFoundException;
import com.knowledgehub.model.Document;
import com.knowledgehub.model.DocumentStatus;
import com.knowledgehub.repository.JobRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

class SummaryServiceTest {
    private final DocumentPermissionService perms = mock(DocumentPermissionService.class);
    private final JobService jobs = new JobService(new JobRepository(), Clock.systemUTC());
    private final LlmClient llm = mock(LlmClient.class);
    private final SummaryWorker worker = new SummaryWorker(perms, jobs, new PromptBuilder(), llm);
    private final UUID doc = UUID.randomUUID();

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    @Test
    void requestCreatesQueuedJobAndWorkerCompletesIt() {
        SummaryWorker asyncWorker = mock(SummaryWorker.class);
        SummaryService service = new SummaryService(perms, jobs, asyncWorker);
        JobResponse job = service.requestSummary(doc, "alice");
        assertThat(job.status()).isEqualTo("QUEUED");
        verify(asyncWorker).summarize(job.jobId(), doc);

        // now run the real worker as the executor thread would (context propagated)
        UserContext.set("alice");
        when(perms.requireReadable(doc, "alice")).thenReturn(new Document(doc, "alice", "T", "body", DocumentStatus.READY, Instant.EPOCH));
        when(llm.complete(any(), eq(false))).thenReturn("- summary");
        worker.summarize(job.jobId(), doc).join();
        assertThat(service.getJob(job.jobId(), "alice").status()).isEqualTo("SUCCEEDED");
        assertThat(service.getJob(job.jobId(), "alice").result()).isEqualTo("- summary");
    }

    @Test
    void otherUsersCannotSeeJob() {
        SummaryService service = new SummaryService(perms, jobs, mock(SummaryWorker.class));
        JobResponse job = service.requestSummary(doc, "alice");
        assertThatThrownBy(() -> service.getJob(job.jobId(), "bob")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void foreignDocumentRejectedBeforeJobCreated() {
        when(perms.requireReadable(doc, "bob")).thenThrow(new NotFoundException("Document not found"));
        SummaryWorker asyncWorker = mock(SummaryWorker.class);
        assertThatThrownBy(() -> new SummaryService(perms, jobs, asyncWorker).requestSummary(doc, "bob"))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(asyncWorker);
    }

    @Test
    void saturatedExecutorPropagatesAndMarksJobFailed() {
        SummaryWorker asyncWorker = mock(SummaryWorker.class);
        when(asyncWorker.summarize(any(), any())).thenThrow(new TaskRejectedException("full"));
        assertThatThrownBy(() -> new SummaryService(perms, jobs, asyncWorker).requestSummary(doc, "alice"))
                .isInstanceOf(TaskRejectedException.class);
    }

    @Test
    void workerFailureMarksJobFailed() {
        JobService js = jobs;
        var job = js.create("alice", doc);
        UserContext.set("alice");
        when(perms.requireReadable(doc, "alice")).thenThrow(new NotFoundException("Document not found"));
        assertThat(worker.summarize(job.id(), doc)).isCompletedExceptionally();
        assertThat(js.get(job.id(), "alice").status().name()).isEqualTo("FAILED");
    }
}
