package com.knowledgehub.dto;

import java.util.UUID;

public record Citation(UUID documentId, int chunkIndex) {
}
