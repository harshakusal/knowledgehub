"""All configuration from environment variables. No secrets in code."""
from __future__ import annotations

import os
from dataclasses import dataclass, field


def _env(name: str, default: str) -> str:
    return os.environ.get(name, default)


@dataclass(frozen=True)
class LlmSettings:
    base_url: str = field(default_factory=lambda: _env("LLM_BASE_URL", "http://localhost:4000"))
    api_key: str = field(default_factory=lambda: _env("LLM_API_KEY", ""), repr=False)  # never in repr/logs
    model: str = field(default_factory=lambda: _env("LLM_MODEL", "mock-1"))
    embedding_dim: int = field(default_factory=lambda: int(_env("LLM_EMBEDDING_DIM", "384")))
    connect_timeout: float = field(default_factory=lambda: float(_env("LLM_CONNECT_TIMEOUT_S", "2")))
    read_timeout: float = field(default_factory=lambda: float(_env("LLM_READ_TIMEOUT_S", "30")))
    max_attempts: int = 4
    initial_backoff: float = 0.2
    max_backoff: float = 5.0
    max_retry_after: float = 10.0
    breaker_failure_rate: float = 0.5
    breaker_min_calls: int = 10
    breaker_window: int = 20
    breaker_open_seconds: float = 20.0


@dataclass(frozen=True)
class Settings:
    database_url: str = field(default_factory=lambda: _env(
        "DATABASE_URL", "postgresql://kh:kh@localhost:5432/knowledgehub_py"), repr=False)
    workers: int = field(default_factory=lambda: int(_env("LLM_WORKERS", "4")))
    queue_capacity: int = field(default_factory=lambda: int(_env("LLM_QUEUE_CAPACITY", "100")))
    max_concurrent_streams: int = field(default_factory=lambda: int(_env("MAX_CONCURRENT_STREAMS", "16")))
    top_k: int = 5
    chunk_size: int = 500
    chunk_overlap: int = 50
    max_upload_bytes: int = field(default_factory=lambda: int(_env("MAX_UPLOAD_BYTES", str(5 * 1024 * 1024))))
    llm: LlmSettings = field(default_factory=LlmSettings)
