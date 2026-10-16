"""Document use-cases (upload/list/get/delete). Historical name kept to limit refactor blast radius;
permission logic now lives in DocumentPermissionService (see PROMPTS.md)."""
from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Callable
from uuid import UUID

from app.errors import BadRequest, Busy, NotFound
from app.models import Document
from app.repositories.document_repository import DocumentRepository
from app.schemas import DocumentDetailResponse, DocumentResponse
from app.services.document_permission_service import DocumentPermissionService
from app.services.ingestion_service import IngestionService
from app.services.task_queue import BoundedTaskQueue

MAX_CHARS = 1_000_000
ALLOWED_EXTENSIONS = {"txt", "md"}


class LegacyDocumentService:
    def __init__(self, documents: DocumentRepository, permissions: DocumentPermissionService,
                 ingestion: IngestionService, queue: BoundedTaskQueue,
                 clock: Callable[[], datetime] = lambda: datetime.now(timezone.utc)):
        self._documents, self._permissions, self._ingestion, self._queue, self._clock = (
            documents, permissions, ingestion, queue, clock)

    async def upload(self, user_id: str, title: str | None, filename: str | None, data: bytes) -> DocumentResponse:
        if not data:
            raise BadRequest("File is empty")
        ext = filename.rsplit(".", 1)[-1].lower() if filename and "." in filename else ""
        if ext not in ALLOWED_EXTENSIONS:
            raise BadRequest("Only .txt and .md files are supported")
        try:
            content = data.decode("utf-8", errors="strict")
        except UnicodeDecodeError:
            raise BadRequest("File must be UTF-8 text") from None
        if not content.strip():
            raise BadRequest("File has no text")
        if len(content) > MAX_CHARS:
            raise BadRequest("Document too long")
        effective = title.strip() if title and title.strip() else filename
        if len(effective) > 255:
            raise BadRequest("Title too long")
        doc = Document(uuid.uuid4(), user_id, effective, content, "PENDING", self._clock())
        await self._documents.insert(doc)
        try:
            self._queue.submit(lambda: self._ingestion.ingest(doc.id))
        except Busy:
            await self._documents.update_status(doc.id, "FAILED")
            raise
        return DocumentResponse.of(doc)

    async def list(self, user_id: str, query: str | None) -> list[DocumentResponse]:
        q = (query or "").strip()
        if len(q) > 100:
            raise BadRequest("Query too long")
        return [DocumentResponse.of(d) for d in await self._documents.find_by_owner(user_id, q, 200)]

    async def get(self, doc_id: UUID, user_id: str) -> DocumentDetailResponse:
        return DocumentDetailResponse.of(await self._permissions.require_readable(doc_id, user_id))

    async def delete(self, doc_id: UUID, user_id: str) -> None:
        await self._permissions.require_readable(doc_id, user_id)
        if not await self._documents.delete_by_id_and_owner(doc_id, user_id):
            raise NotFound("Document not found")
