package com.knowledgehub.service;

import com.knowledgehub.dto.DocumentDetailResponse;
import com.knowledgehub.dto.DocumentResponse;
import com.knowledgehub.exception.BadRequestException;
import com.knowledgehub.exception.NotFoundException;
import com.knowledgehub.model.Document;
import com.knowledgehub.model.DocumentStatus;
import com.knowledgehub.repository.DocumentRepository;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

/**
 * Document use-cases (upload, list, get, delete). Kept its historical name to limit the refactor's blast
 * radius; permission logic was extracted into {@link DocumentPermissionService} (see PROMPTS.md).
 */
@Service
public class LegacyDocumentService {
    static final int MAX_CHARS = 1_000_000;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("txt", "md");

    private final DocumentRepository documents;
    private final DocumentPermissionService permissions;
    private final IngestionWorker ingestion;
    private final Clock clock;

    public LegacyDocumentService(DocumentRepository documents, DocumentPermissionService permissions,
                                 IngestionWorker ingestion, Clock clock) {
        this.documents = documents;
        this.permissions = permissions;
        this.ingestion = ingestion;
        this.clock = clock;
    }

    public DocumentResponse upload(String userId, String title, String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BadRequestException("File is empty");
        }
        String ext = filename == null || !filename.contains(".") ? ""
                : filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BadRequestException("Only .txt and .md files are supported");
        }
        String content = decodeUtf8(bytes);
        if (content.isBlank()) {
            throw new BadRequestException("File has no text");
        }
        if (content.length() > MAX_CHARS) {
            throw new BadRequestException("Document too long");
        }
        String effectiveTitle = (title == null || title.isBlank()) ? filename : title.strip();
        if (effectiveTitle.length() > 255) {
            throw new BadRequestException("Title too long");
        }
        Document doc = new Document(UUID.randomUUID(), userId, effectiveTitle, content, DocumentStatus.PENDING, clock.instant());
        documents.insert(doc);
        try {
            ingestion.ingest(doc.id()); // async on llmExecutor; status moves to READY/FAILED
        } catch (TaskRejectedException e) {
            documents.updateStatus(doc.id(), DocumentStatus.FAILED);
            throw e;
        }
        return DocumentResponse.from(doc);
    }

    public List<DocumentResponse> list(String userId, String query) {
        String q = query == null ? "" : query.strip();
        if (q.length() > 100) {
            throw new BadRequestException("Query too long");
        }
        return documents.findByOwner(userId, q, 200).stream().map(DocumentResponse::from).toList();
    }

    public DocumentDetailResponse get(UUID id, String userId) {
        return DocumentDetailResponse.from(permissions.requireReadable(id, userId));
    }

    public void delete(UUID id, String userId) {
        permissions.requireReadable(id, userId);
        if (!documents.deleteByIdAndOwner(id, userId)) {
            throw new NotFoundException("Document not found");
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new BadRequestException("File must be UTF-8 text");
        }
    }
}
