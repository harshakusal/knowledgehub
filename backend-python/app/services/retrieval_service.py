from __future__ import annotations

from uuid import UUID

from app.clients.llm_client import LlmClient
from app.models import RetrievedChunk
from app.repositories.chunk_repository import ChunkRepository
from app.services.legacy_document_service import LegacyDocumentService


class RetrievalService:
    def __init__(self, llm: LlmClient, chunks: ChunkRepository, permissions: LegacyDocumentService, top_k: int = 5):
        self._llm, self._chunks, self._permissions, self._top_k = llm, chunks, permissions, top_k

    async def retrieve(self, user_id: str, question: str, document_ids: list[UUID]) -> list[RetrievedChunk]:
        """Top-k similar chunks from the user's OWN documents (optionally limited to ids they own)."""
        scope = await self._permissions.require_all_readable(document_ids, user_id)
        q = (await self._llm.embed([question]))[0]
        return await self._chunks.search_similar(user_id, scope, q, self._top_k)
