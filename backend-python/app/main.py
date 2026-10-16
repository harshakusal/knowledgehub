from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from psycopg_pool import AsyncConnectionPool

from app import container as container_mod
from app.config import Settings
from app.db import migrate
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
        pool = AsyncConnectionPool(settings.database_url, min_size=1, max_size=10, open=False)
        await pool.open()
        await migrate(pool)
        c = container_mod.build(settings, pool)
        c.queue.start()
        app.state.container = c
        log.info("KnowledgeHub (Python) started; LLM at %s", settings.llm.base_url)
        yield
        await c.queue.stop()
        await c.llm.aclose()
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
