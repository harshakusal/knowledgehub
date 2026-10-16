from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app import container as container_mod
from app.config import Settings
from app.errors import AppError, Busy, LlmClientError, LlmUnavailable
from app.routers import chat, documents

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("knowledgehub")


def problem(status: int, title: str, detail: str, headers: dict | None = None) -> JSONResponse:
    return JSONResponse({"title": title, "status": status, "detail": detail}, status_code=status,
                        media_type="application/problem+json", headers=headers)


def register_handlers(app: FastAPI) -> None:
    @app.exception_handler(AppError)
    async def app_error(_: Request, e: AppError):
        headers = {"Retry-After": "5"} if isinstance(e, (Busy, LlmUnavailable)) else None
        if isinstance(e, LlmClientError):
            log.error("LLM rejected request with status %s", e.upstream_status)
            return problem(502, e.title, "Upstream LLM rejected the request")
        return problem(e.status, e.title, e.detail, headers)

    @app.exception_handler(RequestValidationError)
    async def invalid(_: Request, e: RequestValidationError):
        detail = "; ".join(f"{'.'.join(str(p) for p in err['loc'][1:])}: {err['msg']}" for err in e.errors())
        return problem(400, "Bad Request", detail or "Validation failed")


def create_app(settings: Settings | None = None, use_lifespan: bool = True) -> FastAPI:
    settings = settings or Settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        pool = None
        if settings.storage == "postgres":
            from psycopg_pool import AsyncConnectionPool  # lazy: memory mode never touches psycopg

            from app.db import migrate
            from app.repositories.chunk_repository import ChunkRepository
            from app.repositories.document_repository import DocumentRepository

            pool = AsyncConnectionPool(settings.database_url, min_size=1, max_size=10, open=False)
            await pool.open()
            await migrate(pool)
            doc_repo, chunk_repo = DocumentRepository(pool), ChunkRepository(pool)
        else:
            from app.repositories.memory import InMemoryChunkRepository, InMemoryDocumentRepository

            chunk_repo = InMemoryChunkRepository()
            doc_repo = InMemoryDocumentRepository(chunk_repo)
            log.warning("STORAGE=memory: data is kept in RAM and lost on restart (no database configured)")
        c = container_mod.build(settings, doc_repo, chunk_repo)
        c.queue.start()
        app.state.container = c
        log.info("KnowledgeHub (Python) started; storage=%s; LLM at %s", settings.storage, settings.llm.base_url)
        yield
        await c.queue.stop()
        await c.llm.aclose()
        if pool is not None:
            await pool.close()

    app = FastAPI(title="KnowledgeHub", lifespan=lifespan if use_lifespan else None)
    register_handlers(app)
    app.include_router(documents.router)
    app.include_router(chat.router)

    @app.get("/actuator/health")
    async def health():
        return {"status": "UP"}

    return app


app = create_app()
