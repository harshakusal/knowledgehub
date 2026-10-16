"""Single place that decides document access (extracted from legacy_document_service.py, see PROMPTS.md).
Rule: only the owner may read/modify. Foreign ids are reported as 404 (not 403) so ids can't be probed."""
from __future__ import annotations

from typing import Iterable
from uuid import UUID

from app.errors import NotFound
from app.models import Document
from app.repositories.document_repository import DocumentRepository


class DocumentPermissionService:
    def __init__(self, documents: DocumentRepository):
        self._documents = documents

    @staticmethod
    def can_read(doc: Document, user_id: str | None) -> bool:
        return user_id is not None and doc.owner_id == user_id

    async def require_readable(self, document_id: UUID, user_id: str) -> Document:
        doc = await self._documents.find_by_id(document_id)
        if doc is None or not self.can_read(doc, user_id):
            raise NotFound("Document not found")
        return doc

    async def require_all_readable(self, document_ids: Iterable[UUID], user_id: str) -> list[UUID]:
        ids = list(document_ids)
        for i in ids:
            await self.require_readable(i, user_id)
        return ids
