package com.knowledgehub.dto;

import com.knowledgehub.model.Job;
import java.time.Instant;
import java.util.UUID;

public record JobResponse(UUID jobId, UUID documentId, String status, String result, String error, Instant createdAt) {
    public static JobResponse from(Job j) {
        return new JobResponse(j.id(), j.documentId(), j.status().name(), j.result(), j.error(), j.createdAt());
    }
}
