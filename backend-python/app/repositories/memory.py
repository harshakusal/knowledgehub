"""In-memory repositories (STORAGE=memory) with the SAME async interface as the Postgres ones.
For machines without database access (e.g. locked-down office networks). Single-process; data lost on restart.
All access happens on the event loop thread, so plain dicts are safe (no awaits inside mutations)."""
from __future__ import annotations

import math
from dataclasses import dataclass, replace
from typing import Sequence
from uuid import UUID

from app.models import Document, RetrievedChunk, TextChunk


def cosine_distance(a: Sequence[float], b: Sequence[float]) -> float:
    """Same semantics as pgvector's `<=>` (1 - cosine similarity)."""
    if len(a) != len(b):
        raise ValueError("vector dimension mismatch")
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    if na == 0 or nb == 0:
        return 1.0
    return 1.0 - dot / (na * nb)


@dataclass(frozen=True)
class _StoredChunk:
    document_id: UUID
    owner_id: str
    title: str
    section_heading: str | None
    chunk_index: int
    content: str
    embedding: tuple[float, ...]


class InMemoryChunkRepository:
    def __init__(self) -> None:
        self._by_doc: dict[UUID, list[_StoredChunk]] = {}

    async def replace_chunks(self, doc: Document, chunks: list[TextChunk], embeddings: list[list[float]]) -> None:
        if len(chunks) != len(embeddings):
            raise ValueError("chunks/embeddings length mismatch")
        self._by_doc[doc.id] = [
            _StoredChunk(doc.id, doc.owner_id, doc.title, c.section_heading, c.chunk_index, c.text, tuple(e))
            for c, e in zip(chunks, embeddings)]

    async def search_similar(self, owner_id: str, document_ids: list[UUID], query: list[float], k: int) -> list[RetrievedChunk]:
        """Top-k by cosine distance, ALWAYS restricted to the owner (and optionally to document_ids)."""
        wanted = set(document_ids)
        scored = []
        for doc_id, rows in self._by_doc.items():
            if wanted and doc_id not in wanted:
                continue
            for r in rows:
                if r.owner_id != owner_id:
                    continue
                scored.append((cosine_distance(r.embedding, query), r))
        scored.sort(key=lambda t: t[0])
        return [RetrievedChunk(r.document_id, r.chunk_index, r.title, r.section_heading, r.content, d)
                for d, r in scored[:k]]

    def delete_for_document(self, doc_id: UUID) -> None:
        self._by_doc.pop(doc_id, None)

    def count(self, doc_id: UUID) -> int:
        return len(self._by_doc.get(doc_id, []))


class InMemoryDocumentRepository:
    """Every list query is owner-scoped; deleting a document cascades to its chunks (like ON DELETE CASCADE)."""

    def __init__(self, chunks: InMemoryChunkRepository | None = None) -> None:
        self._docs: dict[UUID, Document] = {}
        self._chunks = chunks

    async def insert(self, d: Document) -> None:
        if d.id in self._docs:
            raise ValueError("duplicate id")
        self._docs[d.id] = d

    async def find_by_id(self, doc_id: UUID) -> Document | None:
        return self._docs.get(doc_id)

    async def find_by_owner(self, owner_id: str, title_query: str, limit: int) -> list[Document]:
        q = (title_query or "").lower()
        rows = [d for d in self._docs.values() if d.owner_id == owner_id and q in d.title.lower()]
        rows.sort(key=lambda d: d.created_at, reverse=True)
        return rows[:limit]

    async def update_status(self, doc_id: UUID, status: str) -> None:
        if doc_id in self._docs:
            self._docs[doc_id] = replace(self._docs[doc_id], status=status)

    async def delete_by_id_and_owner(self, doc_id: UUID, owner_id: str) -> bool:
        d = self._docs.get(doc_id)
        if d is None or d.owner_id != owner_id:
            return False
        del self._docs[doc_id]
        if self._chunks is not None:
            self._chunks.delete_for_document(doc_id)
        return True
