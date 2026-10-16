package com.knowledgehub.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.knowledgehub.exception.NotFoundException;
import com.knowledgehub.model.Document;
import com.knowledgehub.model.DocumentStatus;
import com.knowledgehub.repository.DocumentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentPermissionServiceTest {
    private final DocumentRepository repo = mock(DocumentRepository.class);
    private final DocumentPermissionService service = new DocumentPermissionService(repo);
    private final UUID id = UUID.randomUUID();
    private final Document aliceDoc = new Document(id, "alice", "t", "c", DocumentStatus.READY, Instant.EPOCH);

    @Test
    void ownerCanRead() {
        when(repo.findById(id)).thenReturn(Optional.of(aliceDoc));
        assertThat(service.requireReadable(id, "alice")).isEqualTo(aliceDoc);
    }

    @Test
    void otherUserGets404NotForbidden() {
        when(repo.findById(id)).thenReturn(Optional.of(aliceDoc));
        assertThatThrownBy(() -> service.requireReadable(id, "bob")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void missingDocumentIs404() {
        when(repo.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requireReadable(id, "alice")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void nullUserCannotRead() {
        assertThat(service.canRead(aliceDoc, null)).isFalse();
    }

    @Test
    void requireAllReadableRejectsIfAnyForeign() {
        UUID other = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.of(aliceDoc));
        when(repo.findById(other)).thenReturn(Optional.of(new Document(other, "bob", "t", "c", DocumentStatus.READY, Instant.EPOCH)));
        assertThat(service.requireAllReadable(List.of(id), "alice")).containsExactly(id);
        assertThatThrownBy(() -> service.requireAllReadable(List.of(id, other), "alice")).isInstanceOf(NotFoundException.class);
    }
}
