package com.knowledgehub.model;

import java.time.Instant;
import java.util.UUID;

public record Job(UUID id, String ownerId, UUID documentId, JobStatus status, String result, String error, Instant createdAt) {
    public Job withStatus(JobStatus s, String result, String error) {
        return new Job(id, ownerId, documentId, s, result, error, createdAt);
    }
}
