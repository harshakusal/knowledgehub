"""Pydantic DTOs: the only types that cross the HTTP boundary. camelCase on the wire (same contract as Java)."""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator
from pydantic.alias_generators import to_camel

from app.models import Document, Job


class Dto(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, frozen=True)


class DocumentResponse(Dto):
    id: UUID
    title: str
    status: str
    created_at: datetime
    size_chars: int

    @classmethod
    def of(cls, d: Document) -> "DocumentResponse":
        return cls(id=d.id, title=d.title, status=d.status, created_at=d.created_at.astimezone(timezone.utc), size_chars=len(d.content))


class DocumentDetailResponse(Dto):
    id: UUID
    title: str
    status: str
    created_at: datetime
    content: str

    @classmethod
    def of(cls, d: Document) -> "DocumentDetailResponse":
        return cls(id=d.id, title=d.title, status=d.status, created_at=d.created_at.astimezone(timezone.utc), content=d.content)


class ChatRequest(Dto):
    question: str = Field(max_length=2000)
    document_ids: list[UUID] = Field(default_factory=list, max_length=20)

    @field_validator("question")
    @classmethod
    def not_blank(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("must not be blank")
        return v

    @field_validator("document_ids", mode="before")
    @classmethod
    def none_to_empty(cls, v):
        return [] if v is None else v


class Citation(Dto):
    document_id: UUID
    chunk_index: int


class RagAnswer(Dto):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, frozen=True, extra="ignore")
    answer: str = Field(min_length=1)
    citations: list[Citation]
    confidence: Literal["high", "medium", "low"]
    answer_found: bool

    @classmethod
    def not_found(cls) -> "RagAnswer":
        return cls(answer="I couldn't find a reliable answer to that in your documents.",
                   citations=[], confidence="low", answer_found=False)


class JobResponse(Dto):
    job_id: UUID
    document_id: UUID
    status: str
    result: str | None
    error: str | None
    created_at: datetime

    @classmethod
    def of(cls, j: Job) -> "JobResponse":
        return cls(job_id=j.id, document_id=j.document_id, status=j.status, result=j.result,
                   error=j.error, created_at=j.created_at)
