package com.knowledgehub.dto;

import com.knowledgehub.model.Document;
import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(UUID id, String title, String status, Instant createdAt, int sizeChars) {
    public static DocumentResponse from(Document d) {
        return new DocumentResponse(d.id(), d.title(), d.status().name(), d.createdAt(), d.content().length());
    }
}
