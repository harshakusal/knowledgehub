"""Composition root: builds the object graph once (lifespan) and exposes it via FastAPI Depends."""
from __future__ import annotations

from dataclasses import dataclass

from app.clients.llm_client import LlmClient
from app.config import Settings
from app.repositories.job_repository import JobRepository
from app.services.chat_stream_service import ChatStreamService
from app.services.chunking_service import ChunkingService
from app.services.ingestion_service import IngestionService
from app.services.job_service import JobService
from app.services.legacy_document_service import LegacyDocumentService
from app.services.prompt_builder import PromptBuilder
from app.services.rag_service import RagService
from app.services.retrieval_service import RetrievalService
from app.services.stream_limiter import StreamLimiter
from app.services.summary_service import SummaryService, SummaryWorker
from app.services.task_queue import BoundedTaskQueue


@dataclass
class Container:
    llm: LlmClient
    queue: BoundedTaskQueue
    documents: LegacyDocumentService
    permissions: LegacyDocumentService
    summaries: SummaryService
    chat: ChatStreamService
    rag: RagService
    streams: StreamLimiter


def build(settings: Settings, doc_repo, chunk_repo) -> Container:
    """doc_repo/chunk_repo: Postgres or in-memory implementations (same interface)."""
    llm = LlmClient(settings.llm)
    queue = BoundedTaskQueue(settings.workers, settings.queue_capacity)
    prompts = PromptBuilder()
    ingestion = IngestionService(doc_repo, chunk_repo, ChunkingService(settings.chunk_size, settings.chunk_overlap), llm)
    permissions = LegacyDocumentService(doc_repo, ingestion, queue)
    retrieval = RetrievalService(llm, chunk_repo, permissions, settings.top_k)
    jobs = JobService(JobRepository())
    return Container(
        llm=llm, queue=queue,
        documents=permissions,
        permissions=permissions,
        summaries=SummaryService(permissions, jobs, SummaryWorker(permissions, jobs, prompts, llm), queue),
        chat=ChatStreamService(retrieval, prompts, llm),
        rag=RagService(retrieval, prompts, llm),
        streams=StreamLimiter(settings.max_concurrent_streams),
    )
