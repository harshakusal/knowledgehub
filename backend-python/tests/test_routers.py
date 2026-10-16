"""Router tests with a container of fakes (no DB): auth, DTO-only output, status codes, SSE contract."""
import uuid
from datetime import datetime, timezone

import pytest
from fastapi.testclient import TestClient

from app.container import Container
from app.errors import LlmUnavailable
from app.main import create_app
from app.models import Document, RetrievedChunk
from app.repositories.job_repository import JobRepository
from app.services.chat_stream_service import ChatStreamService
from app.services.document_permission_service import DocumentPermissionService
from app.services.job_service import JobService
from app.services.legacy_document_service import LegacyDocumentService
from app.services.prompt_builder import PromptBuilder
from app.services.rag_service import RagService
from app.services.stream_limiter import StreamLimiter
from app.services.summary_service import SummaryService, SummaryWorker
from tests.fakes import FakeDocumentRepository, FakeLlm
from tests.test_services import RecordingQueue, StubRetrieval

A = {"X-User-Id": "alice"}
B = {"X-User-Id": "bob"}


@pytest.fixture
def env():
    repo = FakeDocumentRepository()
    d = Document(uuid.uuid4(), "alice", "Policy", "abc", "READY", datetime(2026, 1, 1, tzinfo=timezone.utc))
    repo.docs[d.id] = d
    perms = DocumentPermissionService(repo)
    jobs = JobService(JobRepository())
    queue = RecordingQueue()
    llm = FakeLlm(tokens=["Twenty ", "days."],
                  completions=['{"answer":"x","citations":[],"confidence":"low","answerFound":false}'])
    retrieval = StubRetrieval([RetrievedChunk(d.id, 0, "Policy", None, "abc", 0.1)])
    c = Container(llm=llm, queue=queue,
                  documents=LegacyDocumentService(repo, perms, None, queue),
                  permissions=perms,
                  summaries=SummaryService(perms, jobs, SummaryWorker(perms, jobs, PromptBuilder(), llm), queue),
                  chat=ChatStreamService(retrieval, PromptBuilder(), llm),
                  rag=RagService(retrieval, PromptBuilder(), llm),
                  streams=StreamLimiter(2))
    app = create_app(use_lifespan=False)
    app.state.container = c
    return TestClient(app), d, c


def test_requires_valid_user_header(env):
    client, _, _ = env
    assert client.get("/api/documents").status_code == 401
    assert client.get("/api/documents", headers={"X-User-Id": "bad id!"}).status_code == 401


def test_list_is_scoped_and_dto_only(env):
    client, d, _ = env
    body = client.get("/api/documents?q=pol", headers=A).json()
    assert body == [{"id": str(d.id), "title": "Policy", "status": "READY",
                     "createdAt": "2026-01-01T00:00:00Z", "sizeChars": 3}]
    assert client.get("/api/documents", headers=B).json() == []


def test_foreign_or_missing_is_404_and_bad_id_400(env):
    client, d, _ = env
    assert client.get(f"/api/documents/{d.id}", headers=B).status_code == 404
    assert client.delete(f"/api/documents/{d.id}", headers=B).status_code == 404
    assert client.get(f"/api/documents/{uuid.uuid4()}", headers=A).status_code == 404
    r = client.get("/api/documents/not-a-uuid", headers=A)
    assert r.status_code == 400 and r.json()["status"] == 400


def test_upload_201_and_delete_204(env):
    client, _, c = env
    r = client.post("/api/documents", headers=A, files={"file": ("a.txt", b"hi", "text/plain")}, data={"title": "T"})
    assert r.status_code == 201 and r.headers["location"] == f"/api/documents/{r.json()['id']}"
    assert r.json()["status"] == "PENDING" and len(c.queue.jobs) == 1
    assert client.post("/api/documents", headers=A, files={"file": ("a.pdf", b"x")}).status_code == 400
    assert client.delete(f"/api/documents/{r.json()['id']}", headers=A).status_code == 204


def test_summary_202_and_job_lookup_owner_scoped(env):
    client, d, _ = env
    r = client.post(f"/api/documents/{d.id}/summary", headers=A)
    assert r.status_code == 202
    job_id = r.json()["jobId"]
    assert r.headers["location"] == f"/api/jobs/{job_id}"
    assert client.get(f"/api/jobs/{job_id}", headers=A).json()["status"] == "QUEUED"
    assert client.get(f"/api/jobs/{job_id}", headers=B).status_code == 404
    assert client.post(f"/api/documents/{d.id}/summary", headers=B).status_code == 404


def test_busy_queue_is_503_with_retry_after(env):
    client, d, c = env
    c.queue.full = True
    r = client.post(f"/api/documents/{d.id}/summary", headers=A)
    assert r.status_code == 503 and r.headers["retry-after"] == "5"


def test_stream_sse_contract(env):
    client, _, _ = env
    r = client.post("/api/chat/stream", headers=A, json={"question": "leave?", "documentIds": []})
    assert r.status_code == 200 and r.headers["content-type"].startswith("text/event-stream")
    assert r.text.endswith("event: done\ndata: {}\n\n")
    assert 'event: token\ndata: {"t":"Twenty "}' in r.text


def test_stream_validation_and_foreign_doc(env):
    client, d, _ = env
    assert client.post("/api/chat/stream", headers=A, json={"question": "  "}).status_code == 400
    assert client.post("/api/chat/stream", headers=B, json={"question": "q", "documentIds": [str(d.id)]}).status_code == 404


def test_stream_concurrency_cap(env):
    client, _, c = env
    c.streams.active = c.streams.limit
    assert client.post("/api/chat/stream", headers=A, json={"question": "q"}).status_code == 503


def test_structured_answer(env):
    client, _, _ = env
    r = client.post("/api/chat/answer", headers=A, json={"question": "q"})
    assert r.json() == {"answer": "x", "citations": [], "confidence": "low", "answerFound": False}


def test_llm_unavailable_maps_to_503(env):
    client, _, c = env

    async def boom(*a, **k):
        raise LlmUnavailable("The AI service is temporarily unavailable (circuit open).")
    c.rag.answer = boom
    r = client.post("/api/chat/answer", headers=A, json={"question": "q"})
    assert r.status_code == 503 and "circuit open" in r.json()["detail"]
