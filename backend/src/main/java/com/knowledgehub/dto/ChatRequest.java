package com.knowledgehub.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record ChatRequest(
        @NotBlank @Size(max = 2000) String question,
        @Size(max = 20) List<UUID> documentIds) {
    public List<UUID> documentIdsOrEmpty() {
        return documentIds == null ? List.of() : documentIds;
    }
}
