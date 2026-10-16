package com.knowledgehub.controller;

import com.knowledgehub.config.UserContext;
import com.knowledgehub.dto.DocumentDetailResponse;
import com.knowledgehub.dto.DocumentResponse;
import com.knowledgehub.dto.JobResponse;
import com.knowledgehub.exception.BadRequestException;
import com.knowledgehub.service.LegacyDocumentService;
import com.knowledgehub.service.SummaryService;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Thin HTTP layer: resolve user, validate input, delegate to services, return DTO records. */
@RestController
@RequestMapping("/api/documents")
@Validated
public class DocumentController {
    private final LegacyDocumentService documents;
    private final SummaryService summaries;

    public DocumentController(LegacyDocumentService documents, SummaryService summaries) {
        this.documents = documents;
        this.summaries = summaries;
    }

    @GetMapping
    public List<DocumentResponse> list(@RequestParam(name = "q", required = false) @Size(max = 100) String query) {
        return documents.list(UserContext.require(), query);
    }

    @GetMapping("/{id}")
    public DocumentDetailResponse get(@PathVariable UUID id) {
        return documents.get(id, UserContext.require());
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(@RequestPart("file") MultipartFile file,
                                                   @RequestParam(name = "title", required = false) @Size(max = 255) String title) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BadRequestException("Could not read upload");
        }
        DocumentResponse created = documents.upload(UserContext.require(), title, file.getOriginalFilename(), bytes);
        return ResponseEntity.created(URI.create("/api/documents/" + created.id())).body(created);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        documents.delete(id, UserContext.require());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/summary")
    public ResponseEntity<JobResponse> summarize(@PathVariable UUID id) {
        JobResponse job = summaries.requestSummary(id, UserContext.require());
        return ResponseEntity.accepted().location(URI.create("/api/jobs/" + job.jobId())).body(job);
    }
}
