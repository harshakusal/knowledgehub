from __future__ import annotations

import logging
from uuid import UUID

from app.clients.llm_client import LlmClient
from app.repositories.chunk_repository import ChunkRepository
from app.repositories.document_repository import DocumentRepository
from app.services.chunking_service import ChunkingService

log = logging.getLogger(__name__)
BATCH = 32


class IngestionService:
    """chunk -> embed (batched) -> store in pgvector; status READY or FAILED. Runs on the task queue."""

    def __init__(self, documents: DocumentRepository, chunks: ChunkRepository, chunker: ChunkingService, llm: LlmClient):
        self._documents, self._chunks, self._chunker, self._llm = documents, chunks, chunker, llm

    async def ingest(self, document_id: UUID) -> str:
        doc = await self._documents.find_by_id(document_id)
        if doc is None:
            return "FAILED"
        try:
            parts = self._chunker.chunk(doc.content)
            vectors: list[list[float]] = []
            for i in range(0, len(parts), BATCH):
                vectors.extend(await self._llm.embed([p.text for p in parts[i:i + BATCH]]))
            await self._chunks.replace_chunks(doc, parts, vectors)
            await self._documents.update_status(document_id, "READY")
            log.info("Ingested document %s into %d chunks", document_id, len(parts))
            return "READY"
        except Exception as e:
            log.warning("Ingestion failed for %s: %s", document_id, e)
            await self._documents.update_status(document_id, "FAILED")
            return "FAILED"
