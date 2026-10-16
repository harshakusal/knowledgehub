package com.knowledgehub.service;

import com.knowledgehub.exception.NotFoundException;
import com.knowledgehub.model.Document;
import com.knowledgehub.repository.DocumentRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Single place that decides whether a user may access a document (extracted from LegacyDocumentService,
 * see PROMPTS.md). Rule today: only the owner can read/modify. A document owned by someone else is reported
 * as 404 (not 403) so ids of other users' documents cannot be probed.
 */
@Service
public class DocumentPermissionService {
    private final DocumentRepository documents;

    public DocumentPermissionService(DocumentRepository documents) {
        this.documents = documents;
    }

    public Document requireReadable(UUID documentId, String userId) {
        return documents.findById(documentId)
                .filter(d -> canRead(d, userId))
                .orElseThrow(() -> new NotFoundException("Document not found"));
    }

    public boolean canRead(Document document, String userId) {
        return userId != null && document.ownerId().equals(userId);
    }

    /** Validates that every requested id is readable by the user; returns the ids unchanged. */
    public List<UUID> requireAllReadable(Collection<UUID> documentIds, String userId) {
        for (UUID id : documentIds) {
            requireReadable(id, userId);
        }
        return List.copyOf(documentIds);
    }
}
