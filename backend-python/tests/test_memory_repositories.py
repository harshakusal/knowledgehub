import uuid
from datetime import datetime, timedelta, timezone

import pytest

from app.config import Settings
from app.models import Document, TextChunk
from app.repositories.memory import InMemoryChunkRepository, InMemoryDocumentRepository, cosine_distance
from app.services.chunking_service import ChunkingService
from app.services.document_permission_service import DocumentPermissionService
from app.services.ingestion_service import IngestionService
from app.services.retrieval_service import RetrievalService
from tests.fakes import FakeLlm

T0 = datetime(2026, 1, 1, tzinfo=timezone.utc)


def doc(owner, title="Policy", minutes=0):
    return Document(uuid.uuid4(), owner, title, "text", "PENDING", T0 + timedelta(minutes=minutes))


def repos():
    chunks = InMemoryChunkRepository()
    return InMemoryDocumentRepository(chunks), chunks


async def test_documents_crud_and_owner_scoping():
    docs, _ = repos()
    a1, a2, b1 = doc("alice", "Leave Policy", 1), doc("alice", "Expenses", 2), doc("bob", "Leave Policy")
    for d in (a1, a2, b1):
        await docs.insert(d)
    assert [d.id for d in await docs.find_by_owner("alice", "", 10)] == [a2.id, a1.id]  # newest first
    assert [d.id for d in await docs.find_by_owner("alice", "LEAVE", 10)] == [a1.id]
    assert [d.id for d in await docs.find_by_owner("bob", "", 10)] == [b1.id]
    assert len(await docs.find_by_owner("alice", "", 1)) == 1
    await docs.update_status(a1.id, "READY")
    assert (await docs.find_by_id(a1.id)).status == "READY"
    with pytest.raises(ValueError):
        await docs.insert(a1)


async def test_delete_only_by_owner_and_cascades_chunks():
    docs, chunks = repos()
    a = doc("alice")
    await docs.insert(a)
    await chunks.replace_chunks(a, [TextChunk(0, None, "x")], [[1.0, 0.0]])
    assert await docs.delete_by_id_and_owner(a.id, "bob") is False
    assert chunks.count(a.id) == 1
    assert await docs.delete_by_id_and_owner(a.id, "alice") is True
    assert await docs.find_by_id(a.id) is None and chunks.count(a.id) == 0


async def test_similarity_top_k_is_owner_filtered():
    _, chunks = repos()
    a, b = doc("alice"), doc("bob")
    await chunks.replace_chunks(a, [TextChunk(0, "H", "leave"), TextChunk(1, None, "expenses")], [[1.0, 0.0], [0.0, 1.0]])
    await chunks.replace_chunks(b, [TextChunk(0, None, "bob secret leave")], [[1.0, 0.0]])  # identical vector
    res = await chunks.search_similar("alice", [], [0.9, 0.1], 5)
    assert [(r.document_id, r.chunk_index) for r in res] == [(a.id, 0), (a.id, 1)]
    assert res[0].distance < res[1].distance and res[0].section_heading == "H"
    assert all(r.document_id != b.id for r in res)
    assert len(await chunks.search_similar("alice", [], [1.0, 0.0], 1)) == 1
    assert await chunks.search_similar("alice", [b.id], [1.0, 0.0], 5) == []  # bob's id doesn't widen scope
    assert await chunks.search_similar("carol", [], [1.0, 0.0], 5) == []


async def test_replace_chunks_replaces_previous():
    _, chunks = repos()
    a = doc("alice")
    await chunks.replace_chunks(a, [TextChunk(0, None, "a"), TextChunk(1, None, "b")], [[1, 0], [0, 1]])
    await chunks.replace_chunks(a, [TextChunk(0, None, "c")], [[1, 0]])
    assert chunks.count(a.id) == 1
    with pytest.raises(ValueError):
        await chunks.replace_chunks(a, [TextChunk(0, None, "c")], [])


def test_cosine_distance():
    assert cosine_distance([1, 0], [1, 0]) == pytest.approx(0)
    assert cosine_distance([1, 0], [0, 1]) == pytest.approx(1)
    assert cosine_distance([1, 0], [-1, 0]) == pytest.approx(2)
    assert cosine_distance([0, 0], [1, 0]) == 1.0
    with pytest.raises(ValueError):
        cosine_distance([1], [1, 2])


async def test_end_to_end_ingest_and_retrieve_in_memory():
    docs, chunks = repos()
    a, b = doc("alice"), doc("bob")
    await docs.insert(a); await docs.insert(b)
    llm = FakeLlm()
    ing = IngestionService(docs, chunks, ChunkingService(), llm)
    assert await ing.ingest(a.id) == "READY" and await ing.ingest(b.id) == "READY"
    r = RetrievalService(llm, chunks, DocumentPermissionService(docs), 5)
    assert {c.document_id for c in await r.retrieve("alice", "q", [])} == {a.id}


def test_storage_defaults(monkeypatch):
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.delenv("STORAGE", raising=False)
    assert Settings().storage == "memory"
    monkeypatch.setenv("DATABASE_URL", "postgresql://x@y/z")
    assert Settings().storage == "postgres"
    monkeypatch.setenv("STORAGE", "memory")
    assert Settings().storage == "memory"
    monkeypatch.setenv("STORAGE", "postgres")
    monkeypatch.delenv("DATABASE_URL")
    with pytest.raises(ValueError):
        Settings()
    monkeypatch.setenv("STORAGE", "sqlite")
    with pytest.raises(ValueError):
        Settings()


def test_app_boots_in_memory_mode(monkeypatch):
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.delenv("STORAGE", raising=False)
    from fastapi.testclient import TestClient
    from app.main import create_app
    with TestClient(create_app()) as client:  # runs lifespan: no pool, no migrations
        assert client.get("/actuator/health").json() == {"status": "UP"}
        assert client.get("/api/documents", headers={"X-User-Id": "alice"}).json() == []
        r = client.post("/api/documents", headers={"X-User-Id": "alice"}, files={"file": ("a.md", b"# A\nhello")})
        assert r.status_code == 201
        assert client.get(f"/api/documents/{r.json()['id']}", headers={"X-User-Id": "bob"}).status_code == 404
