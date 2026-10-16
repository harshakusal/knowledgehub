from __future__ import annotations

from typing import Sequence
from uuid import UUID

from psycopg_pool import AsyncConnectionPool

from app.models import Document, RetrievedChunk, TextChunk


def to_vector_literal(v: Sequence[float]) -> str:
    return "[" + ",".join(repr(float(x)) for x in v) + "]"


class ChunkRepository:
    def __init__(self, pool: AsyncConnectionPool):
        self._pool = pool

    async def replace_chunks(self, doc: Document, chunks: list[TextChunk], embeddings: list[list[float]]) -> None:
        async with self._pool.connection() as c, c.transaction():
            await c.execute("DELETE FROM chunks WHERE document_id = %s", (doc.id,))
            for ch, emb in zip(chunks, embeddings, strict=True):
                await c.execute(
                    """INSERT INTO chunks (document_id, owner_id, title, section_heading, chunk_index, content, embedding)
                       VALUES (%s,%s,%s,%s,%s,%s,%s::vector)""",
                    (doc.id, doc.owner_id, doc.title, ch.section_heading, ch.chunk_index, ch.text, to_vector_literal(emb)))

    async def search_similar(self, owner_id: str, document_ids: list[UUID], query: list[float], k: int) -> list[RetrievedChunk]:
        """Top-k cosine similarity, ALWAYS restricted to the owner; optionally to a subset of their documents."""
        sql = ("SELECT document_id, chunk_index, title, section_heading, content, embedding <=> %s::vector AS distance "
               "FROM chunks WHERE owner_id = %s")
        params: list = [to_vector_literal(query), owner_id]
        if document_ids:
            sql += " AND document_id = ANY(%s)"
            params.append(list(document_ids))
        sql += " ORDER BY distance LIMIT %s"
        params.append(k)
        async with self._pool.connection() as c:
            cur = await c.execute(sql, params)
            return [RetrievedChunk(*r) for r in await cur.fetchall()]
