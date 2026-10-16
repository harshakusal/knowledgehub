"""Resilient async client for the (mock) LLM.

* Retries ONLY 429, 503 and connect/read timeouts (+ connection errors); never 400/401/other 4xx.
* Exponential back-off with jitter (base 0.2 s x 2^n, cap 5 s, jitter in [exp/2, exp]), max 4 attempts,
  Retry-After honoured as a lower bound (if it exceeds 10 s we fail fast).
* Hand-rolled count-based circuit breaker (closed/open/half-open) recording only retryable failures.
* Explicit httpx timeouts. API key from env (LLM_API_KEY), sent as Bearer, never logged.
"""
from __future__ import annotations

import asyncio
import json
import logging
import random
import time
from collections import deque
from typing import Any, AsyncIterator, Awaitable, Callable, TypeVar

import httpx

from app.config import LlmSettings
from app.errors import LlmClientError, LlmUnavailable

log = logging.getLogger(__name__)
T = TypeVar("T")


class RetryableLlmError(Exception):
    def __init__(self, message: str, retry_after: float | None = None):
        super().__init__(message)
        self.retry_after = retry_after


class CircuitOpen(Exception):
    pass


class CircuitBreaker:
    CLOSED, OPEN, HALF_OPEN = "CLOSED", "OPEN", "HALF_OPEN"

    def __init__(self, failure_rate: float, min_calls: int, window: int, open_seconds: float,
                 clock: Callable[[], float] = time.monotonic):
        self.failure_rate, self.min_calls, self.open_seconds = failure_rate, min_calls, open_seconds
        self._results: deque[bool] = deque(maxlen=window)
        self._clock = clock
        self.state = self.CLOSED
        self._opened_at = 0.0
        self._trial_in_flight = False

    def before_call(self) -> None:
        if self.state == self.OPEN:
            if self._clock() - self._opened_at >= self.open_seconds:
                self.state = self.HALF_OPEN
            else:
                raise CircuitOpen()
        if self.state == self.HALF_OPEN:
            if self._trial_in_flight:
                raise CircuitOpen()
            self._trial_in_flight = True

    def on_success(self) -> None:
        if self.state == self.HALF_OPEN:
            self.state, self._trial_in_flight = self.CLOSED, False
            self._results.clear()
        self._results.append(True)

    def on_failure(self) -> None:
        if self.state == self.HALF_OPEN:
            self._open()
            return
        self._results.append(False)
        if len(self._results) >= self.min_calls:
            failures = self._results.count(False)
            if failures / len(self._results) >= self.failure_rate:
                self._open()

    def on_ignored(self) -> None:
        """Non-retryable error: not a health signal, but frees a half-open trial slot."""
        if self.state == self.HALF_OPEN:
            self._trial_in_flight = False

    def _open(self) -> None:
        self.state, self._opened_at, self._trial_in_flight = self.OPEN, self._clock(), False
        self._results.clear()
        log.warning("LLM circuit breaker OPEN for %ss", self.open_seconds)


def parse_retry_after(value: str | None) -> float | None:
    if not value:
        return None
    try:
        return float(int(value.strip()))
    except ValueError:
        return None  # HTTP-date form not used by our provider


class LlmClient:
    def __init__(self, settings: LlmSettings, http: httpx.AsyncClient | None = None,
                 sleep: Callable[[float], Awaitable[None]] = asyncio.sleep):
        self.s = settings
        headers = {"Authorization": f"Bearer {settings.api_key}"} if settings.api_key else {}
        self._http = http or httpx.AsyncClient(
            base_url=settings.base_url, headers=headers,
            timeout=httpx.Timeout(connect=settings.connect_timeout, read=settings.read_timeout,
                                  write=settings.read_timeout, pool=settings.connect_timeout))
        self._sleep = sleep
        self.breaker = CircuitBreaker(settings.breaker_failure_rate, settings.breaker_min_calls,
                                      settings.breaker_window, settings.breaker_open_seconds)

    async def aclose(self) -> None:
        await self._http.aclose()

    # ---------- public API ----------
    async def embed(self, inputs: list[str]) -> list[list[float]]:
        body = {"model": self.s.model, "input": inputs}
        data = await self._execute("embeddings", lambda: self._post_json("/v1/embeddings", body))
        vectors = [item["embedding"] for item in data.get("data", [])]
        if len(vectors) != len(inputs) or any(len(v) != self.s.embedding_dim for v in vectors):
            raise LlmClientError(200, "Embedding shape mismatch")
        return vectors

    async def complete(self, messages: list[dict], json_mode: bool = False) -> str:
        body = self._chat_body(messages, stream=False, json_mode=json_mode)
        data = await self._execute("chat", lambda: self._post_json("/v1/chat", body))
        try:
            return data["choices"][0]["message"]["content"] or ""
        except (KeyError, IndexError, TypeError):
            return ""

    async def stream_chat(self, messages: list[dict], is_cancelled: Callable[[], Awaitable[bool]]) -> AsyncIterator[str]:
        """Yields tokens. Retries only before the first token. Stops reading (and closes the upstream
        connection) as soon as ``is_cancelled()`` is true or the consumer stops iterating."""
        body = self._chat_body(messages, stream=True, json_mode=False)
        response = await self._execute("chat-stream", lambda: self._open_stream(body))
        lines = response.aiter_lines()
        try:
            async for line in lines:
                if await is_cancelled():
                    log.info("LLM stream cancelled by client; closing upstream connection")
                    return
                if not line.startswith("data:"):
                    continue
                payload = line[5:].strip()
                if payload == "[DONE]":
                    return
                token = json.loads(payload).get("choices", [{}])[0].get("delta", {}).get("content", "")
                if token:
                    yield token
        except (httpx.TimeoutException, httpx.TransportError) as e:
            raise LlmUnavailable("The AI service connection dropped mid-answer.") from e
        finally:
            await lines.aclose()
            await response.aclose()

    # ---------- internals ----------
    def _chat_body(self, messages: list[dict], stream: bool, json_mode: bool) -> dict[str, Any]:
        body: dict[str, Any] = {"model": self.s.model, "messages": messages, "stream": stream}
        if json_mode:
            body["response_format"] = {"type": "json_object"}
        return body

    @staticmethod
    def _check(resp: httpx.Response) -> None:
        if resp.is_success:
            return
        if resp.status_code in (429, 503):
            raise RetryableLlmError(f"LLM returned {resp.status_code}", parse_retry_after(resp.headers.get("Retry-After")))
        raise LlmClientError(resp.status_code, f"LLM returned non-retryable status {resp.status_code}")

    async def _post_json(self, path: str, body: dict) -> dict:
        resp = await self._http.post(path, json=body)
        self._check(resp)
        return resp.json()

    async def _open_stream(self, body: dict) -> httpx.Response:
        req = self._http.build_request("POST", "/v1/chat", json=body, headers={"Accept": "text/event-stream"})
        resp = await self._http.send(req, stream=True)
        try:
            self._check(resp)
        except Exception:
            await resp.aclose()
            raise
        return resp

    async def _execute(self, op: str, call: Callable[[], Awaitable[T]]) -> T:
        max_attempts = max(1, self.s.max_attempts)
        for attempt in range(1, max_attempts + 1):
            try:
                self.breaker.before_call()
            except CircuitOpen:
                raise LlmUnavailable("The AI service is temporarily unavailable (circuit open). Please try again shortly.")
            try:
                result = await call()
            except (httpx.TimeoutException, httpx.TransportError) as e:
                err = RetryableLlmError(f"LLM I/O failure: {type(e).__name__}")
            except RetryableLlmError as e:
                err = e
            except LlmClientError:
                self.breaker.on_ignored()
                raise
            except BaseException:  # e.g. CancelledError when the client disconnects: not a health signal
                self.breaker.on_ignored()
                raise
            else:
                self.breaker.on_success()
                return result
            self.breaker.on_failure()
            if attempt >= max_attempts:
                log.warning("LLM %s failed after %d attempts: %s", op, attempt, err)
                raise LlmUnavailable("The AI service is busy or unreachable. Please try again shortly.")
            delay = self.backoff(attempt, err.retry_after)
            if delay is None:
                raise LlmUnavailable("The AI service asked us to wait too long. Please try again later.")
            log.info("LLM %s attempt %d/%d failed (%s); retrying in %.0f ms", op, attempt, max_attempts, err, delay * 1000)
            await self._sleep(delay)
        raise AssertionError("unreachable")

    def backoff(self, attempt: int, retry_after: float | None) -> float | None:
        exp = min(self.s.max_backoff, self.s.initial_backoff * (2 ** (attempt - 1)))
        jittered = random.uniform(exp / 2, exp)
        if retry_after is not None:
            if retry_after > self.s.max_retry_after:
                return None
            return max(jittered, retry_after)
        return jittered
