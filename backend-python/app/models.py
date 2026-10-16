"""Internal records (never returned by routers; map to schemas.*)."""
from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import datetime
from uuid import UUID


@dataclass(frozen=True)
class Document:
    id: UUID
    owner_id: str
    title: str
    content: str
    status: str  # PENDING | READY | FAILED
    created_at: datetime


@dataclass(frozen=True)
class TextChunk:
    chunk_index: int
    section_heading: str | None
    text: str


@dataclass(frozen=True)
class RetrievedChunk:
    document_id: UUID
    chunk_index: int
    title: str
    section_heading: str | None
    content: str
    distance: float


@dataclass(frozen=True)
class Job:
    id: UUID
    owner_id: str
    document_id: UUID
    status: str  # QUEUED | RUNNING | SUCCEEDED | FAILED
    result: str | None
    error: str | None
    created_at: datetime

    def with_status(self, status: str, result: str | None = None, error: str | None = None) -> "Job":
        return replace(self, status=status, result=result, error=error)
