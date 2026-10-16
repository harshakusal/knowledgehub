from __future__ import annotations

import uuid
from datetime import datetime, timezone
from uuid import UUID

from app.errors import NotFound
from app.models import Job
from app.repositories.job_repository import JobRepository


class JobService:
    def __init__(self, jobs: JobRepository):
        self._jobs = jobs

    def create(self, owner_id: str, document_id: UUID) -> Job:
        job = Job(uuid.uuid4(), owner_id, document_id, "QUEUED", None, None, datetime.now(timezone.utc))
        self._jobs.save(job)
        return job

    def update(self, job_id: UUID, status: str, result: str | None = None, error: str | None = None) -> None:
        job = self._jobs.find_by_id(job_id)
        if job:
            self._jobs.save(job.with_status(status, result, error))

    def get(self, job_id: UUID, user_id: str) -> Job:
        """Owner-scoped like documents: someone else's job id is a 404."""
        job = self._jobs.find_by_id(job_id)
        if job is None or job.owner_id != user_id:
            raise NotFound("Job not found")
        return job
