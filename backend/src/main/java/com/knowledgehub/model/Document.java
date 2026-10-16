package com.knowledgehub.model;

import java.time.Instant;
import java.util.UUID;

/** Persistence model. Never returned from controllers; map to {@code DocumentResponse}. */
public record Document(UUID id, String ownerId, String title, String content, DocumentStatus status, Instant createdAt) {
}
