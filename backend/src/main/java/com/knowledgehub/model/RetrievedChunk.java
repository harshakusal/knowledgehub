package com.knowledgehub.model;

import java.util.UUID;

public record RetrievedChunk(UUID documentId, int chunkIndex, String title, String sectionHeading, String content, double distance) {
}
