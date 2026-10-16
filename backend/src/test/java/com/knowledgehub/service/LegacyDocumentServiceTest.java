package com.knowledgehub.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.knowledgehub.dto.DocumentResponse;
import com.knowledgehub.exception.BadRequestException;
import com.knowledgehub.exception.NotFoundException;
import com.knowledgehub.model.Document;
import com.knowledgehub.model.DocumentStatus;
import com.knowledgehub.repository.DocumentRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskRejectedException;

/** Characterization tests written BEFORE the permission extraction (see PROMPTS.md) and kept green after it. */
class LegacyDocumentServiceTest {
    private final DocumentRepository repo = mock(DocumentRepository.class);
    private final IngestionWorker ingestion = mock(IngestionWorker.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final LegacyDocumentService service =
            new LegacyDocumentService(repo, new DocumentPermissionService(repo), ingestion, clock);

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void uploadStoresPendingDocumentForOwnerAndStartsIngestion() {
        DocumentResponse r = service.upload("alice", " Leave Policy ", "leave.md", utf8("# Leave\n20 days"));
        ArgumentCaptor<Document> saved = ArgumentCaptor.forClass(Document.class);
        verify(repo).insert(saved.capture());
        assertThat(saved.getValue().ownerId()).isEqualTo("alice");
        assertThat(saved.getValue().title()).isEqualTo("Leave Policy");
        assertThat(saved.getValue().status()).isEqualTo(DocumentStatus.PENDING);
        assertThat(r.status()).isEqualTo("PENDING");
        assertThat(r.createdAt()).isEqualTo(clock.instant());
        verify(ingestion).ingest(saved.getValue().id());
    }

    @Test
    void uploadDefaultsTitleToFilename() {
        assertThat(service.upload("alice", null, "a.txt", utf8("hi")).title()).isEqualTo("a.txt");
    }

    @Test
    void uploadRejectsBadInput() {
        assertThatThrownBy(() -> service.upload("a", null, "a.txt", new byte[0])).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.upload("a", null, "a.pdf", utf8("x"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.upload("a", null, "a.txt", new byte[]{(byte) 0xC3, (byte) 0x28}))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.upload("a", null, "a.txt", utf8("   "))).isInstanceOf(BadRequestException.class);
        verify(repo, never()).insert(any());
    }

    @Test
    void uploadMarksFailedWhenExecutorSaturated() {
        when(ingestion.ingest(any())).thenThrow(new TaskRejectedException("full"));
        assertThatThrownBy(() -> service.upload("a", null, "a.txt", utf8("x"))).isInstanceOf(TaskRejectedException.class);
        verify(repo).updateStatus(any(), eq(DocumentStatus.FAILED));
    }

    @Test
    void listIsScopedToOwner() {
        when(repo.findByOwner("alice", "pol", 200)).thenReturn(List.of(
                new Document(UUID.randomUUID(), "alice", "policy", "abc", DocumentStatus.READY, Instant.EPOCH)));
        assertThat(service.list("alice", " pol ")).extracting(DocumentResponse::title).containsExactly("policy");
        verify(repo).findByOwner("alice", "pol", 200);
    }

    @Test
    void getAndDeleteOfForeignDocumentAre404() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.of(new Document(id, "alice", "t", "c", DocumentStatus.READY, Instant.EPOCH)));
        assertThatThrownBy(() -> service.get(id, "bob")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.delete(id, "bob")).isInstanceOf(NotFoundException.class);
        verify(repo, never()).deleteByIdAndOwner(any(), any());
    }

    @Test
    void ownerCanDelete() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.of(new Document(id, "alice", "t", "c", DocumentStatus.READY, Instant.EPOCH)));
        when(repo.deleteByIdAndOwner(id, "alice")).thenReturn(true);
        service.delete(id, "alice");
        verify(repo).deleteByIdAndOwner(id, "alice");
    }
}
