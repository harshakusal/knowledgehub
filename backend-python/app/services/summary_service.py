from __future__ import annotations

from uuid import UUID

from app.clients.llm_client import LlmClient
from app.context import require_user
from app.errors import Busy
from app.schemas import JobResponse
from app.services.document_permission_service import DocumentPermissionService
from app.services.job_service import JobService
from app.services.prompt_builder import PromptBuilder
from app.services.task_queue import BoundedTaskQueue

MAX_CHARS = 12_000


class SummaryWorker:
    """Runs on the task queue. Re-checks permission using the PROPAGATED contextvar user id."""

    def __init__(self, permissions: DocumentPermissionService, jobs: JobService, prompts: PromptBuilder, llm: LlmClient):
        self._permissions, self._jobs, self._prompts, self._llm = permissions, jobs, prompts, llm

    async def summarize(self, job_id: UUID, document_id: UUID) -> str | None:
        self._jobs.update(job_id, "RUNNING")
        try:
            doc = await self._permissions.require_readable(document_id, require_user())
            summary = await self._llm.complete(self._prompts.summary_prompt(doc.title, doc.content[:MAX_CHARS]))
            self._jobs.update(job_id, "SUCCEEDED", result=summary)
            return summary
        except Exception as e:
            self._jobs.update(job_id, "FAILED", error=getattr(e, "detail", None) or str(e))
            raise


class SummaryService:
    def __init__(self, permissions: DocumentPermissionService, jobs: JobService, worker: SummaryWorker,
                 queue: BoundedTaskQueue):
        self._permissions, self._jobs, self._worker, self._queue = permissions, jobs, worker, queue

    async def request_summary(self, document_id: UUID, user_id: str) -> JobResponse:
        await self._permissions.require_readable(document_id, user_id)
        job = self._jobs.create(user_id, document_id)
        try:
            self._queue.submit(lambda: self._worker.summarize(job.id, document_id))
        except Busy:
            self._jobs.update(job.id, "FAILED", error="Server busy")
            raise
        return JobResponse.of(job)

    def get_job(self, job_id: UUID, user_id: str) -> JobResponse:
        return JobResponse.of(self._jobs.get(job_id, user_id))
