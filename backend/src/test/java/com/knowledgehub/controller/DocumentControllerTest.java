package com.knowledgehub.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.knowledgehub.dto.DocumentResponse;
import com.knowledgehub.dto.JobResponse;
import com.knowledgehub.exception.LlmUnavailableException;
import com.knowledgehub.exception.NotFoundException;
import com.knowledgehub.service.LegacyDocumentService;
import com.knowledgehub.service.SummaryService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {DocumentController.class, JobController.class})
class DocumentControllerTest {
    @Autowired
    MockMvc mvc;
    @MockBean
    LegacyDocumentService documents;
    @MockBean
    SummaryService summaries;

    private final UUID id = UUID.randomUUID();

    @Test
    void requiresUserHeader() throws Exception {
        mvc.perform(get("/api/documents")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/documents").header("X-User-Id", "bad id!")).andExpect(status().isUnauthorized());
        verifyNoInteractions(documents);
    }

    @Test
    void listReturnsDtosForCurrentUserOnly() throws Exception {
        when(documents.list("alice", "pol")).thenReturn(List.of(new DocumentResponse(id, "Policy", "READY", Instant.EPOCH, 3)));
        mvc.perform(get("/api/documents").param("q", "pol").header("X-User-Id", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Policy"))
                .andExpect(jsonPath("$[0].ownerId").doesNotExist())
                .andExpect(jsonPath("$[0].content").doesNotExist());
    }

    @Test
    void foreignOrMissingDocumentIs404Not500() throws Exception {
        when(documents.get(id, "bob")).thenThrow(new NotFoundException("Document not found"));
        mvc.perform(get("/api/documents/" + id).header("X-User-Id", "bob")).andExpect(status().isNotFound());
    }

    @Test
    void malformedIdIs400() throws Exception {
        mvc.perform(get("/api/documents/not-a-uuid").header("X-User-Id", "bob")).andExpect(status().isBadRequest());
    }

    @Test
    void uploadReturns201() throws Exception {
        when(documents.upload(eq("alice"), eq("T"), eq("a.txt"), any()))
                .thenReturn(new DocumentResponse(id, "T", "PENDING", Instant.EPOCH, 2));
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", "a.txt", "text/plain", "hi".getBytes()))
                        .param("title", "T").header("X-User-Id", "alice"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/documents/" + id));
    }

    @Test
    void deleteReturns204() throws Exception {
        mvc.perform(delete("/api/documents/" + id).header("X-User-Id", "alice")).andExpect(status().isNoContent());
        verify(documents).delete(id, "alice");
    }

    @Test
    void summaryReturns202WithJob() throws Exception {
        UUID job = UUID.randomUUID();
        when(summaries.requestSummary(id, "alice")).thenReturn(new JobResponse(job, id, "QUEUED", null, null, Instant.EPOCH));
        mvc.perform(post("/api/documents/" + id + "/summary").header("X-User-Id", "alice"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/jobs/" + job))
                .andExpect(jsonPath("$.jobId").value(job.toString()));
    }

    @Test
    void jobLookup() throws Exception {
        UUID job = UUID.randomUUID();
        when(summaries.getJob(job, "alice")).thenReturn(new JobResponse(job, id, "SUCCEEDED", "sum", null, Instant.EPOCH));
        mvc.perform(get("/api/jobs/" + job).header("X-User-Id", "alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("sum"));
    }

    @Test
    void llmUnavailableIs503WithRetryAfter() throws Exception {
        when(summaries.requestSummary(id, "alice")).thenThrow(new LlmUnavailableException("busy", null));
        mvc.perform(post("/api/documents/" + id + "/summary").header("X-User-Id", "alice"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().exists("Retry-After"));
    }
}
