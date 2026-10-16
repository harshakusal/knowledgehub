from uuid import UUID

from app.models import Job


class JobRepository:
    """In-memory job store (single process). Would move to a table/Redis for multi-instance."""

    def __init__(self) -> None:
        self._jobs: dict[UUID, Job] = {}

    def save(self, job: Job) -> None:
        self._jobs[job.id] = job

    def find_by_id(self, job_id: UUID) -> Job | None:
        return self._jobs.get(job_id)
