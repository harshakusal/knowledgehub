package com.knowledgehub.dto;

import com.knowledgehub.model.Document;
import java.time.Instant;
import java.util.UUID;

public record DocumentDetailResponse(UUID id, String title, String status, Instant createdAt, String content) {
    public static DocumentDetailResponse from(Document d) {
        return new DocumentDetailResponse(d.id(), d.title(), d.status().name(), d.createdAt(), d.content());
    }
}
