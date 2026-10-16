"""In-memory fakes for repositories and the LLM, used by service/router tests (no DB needed)."""
from __future__ import annotations

from uuid import UUID

from app.models import Document, RetrievedChunk, TextChunk


class FakeDocumentRepository:
    def __init__(self):
        self.docs: dict[UUID, Document] = {}
        self.deleted: list[UUID] = []

    async def insert(self, d):
        self.docs[d.id] = d

    async def find_by_id(self, i):
        return self.docs.get(i)

    async def find_by_owner(self, owner, q, limit):
        return [d for d in self.docs.values() if d.owner_id == owner and q.lower() in d.title.lower()][:limit]

    async def update_status(self, i, s):
        from dataclasses import replace
        if i in self.docs:
            self.docs[i] = replace(self.docs[i], status=s)

    async def delete_by_id_and_owner(self, i, owner):
        d = self.docs.get(i)
        if d and d.owner_id == owner:
            del self.docs[i]
            self.deleted.append(i)
            return True
        return False


class FakeChunkRepository:
    def __init__(self):
        self.rows: list[tuple[Document, TextChunk, list[float]]] = []
        self.searches: list[tuple] = []

    async def replace_chunks(self, doc, chunks, embs):
        self.rows = [r for r in self.rows if r[0].id != doc.id] + [(doc, c, e) for c, e in zip(chunks, embs)]

    async def search_similar(self, owner, ids, q, k):
        self.searches.append((owner, list(ids), k))
        return [RetrievedChunk(d.id, c.chunk_index, d.title, c.section_heading, c.text, 0.1)
                for d, c, _ in self.rows if d.owner_id == owner and (not ids or d.id in ids)][:k]


class FakeLlm:
    def __init__(self, completions=None, tokens=None, dim=3):
        self.completions = list(completions or [])
        self.tokens = tokens or []
        self.calls = 0
        self.dim = dim
        self.stream_reads = 0

    async def embed(self, inputs):
        return [[0.1] * self.dim for _ in inputs]

    async def complete(self, messages, json_mode=False):
        self.calls += 1
        return self.completions.pop(0) if self.completions else ""

    async def stream_chat(self, messages, is_cancelled):
        for t in self.tokens:
            if await is_cancelled():
                return
            self.stream_reads += 1
            yield t
