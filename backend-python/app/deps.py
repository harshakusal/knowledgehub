"""FastAPI dependencies (DI). Routers only depend on services, never repositories."""
from __future__ import annotations

import re

from fastapi import Header, Request

from app.container import Container
from app.context import current_user
from app.errors import Unauthenticated
from app.services.chat_stream_service import ChatStreamService
from app.services.legacy_document_service import LegacyDocumentService
from app.services.rag_service import RagService
from app.services.summary_service import SummaryService

_VALID = re.compile(r"^[A-Za-z0-9._-]{1,64}$")


async def current_user_id(x_user_id: str | None = Header(default=None)) -> str:
    """Stub auth: trust X-User-Id (validated). Async so the contextvar is set in the request's own task."""
    if x_user_id is None or not _VALID.match(x_user_id):
        raise Unauthenticated("Missing or invalid X-User-Id header")
    current_user.set(x_user_id)
    return x_user_id


def container(request: Request) -> Container:
    return request.app.state.container


def document_service(request: Request) -> LegacyDocumentService:
    return container(request).documents


def summary_service(request: Request) -> SummaryService:
    return container(request).summaries


def chat_service(request: Request) -> ChatStreamService:
    return container(request).chat


def rag_service(request: Request) -> RagService:
    return container(request).rag
