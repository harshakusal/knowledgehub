"""Bounded in-process task queue for LLM work (ingestion, summaries).

Design: N worker tasks consume an ``asyncio.Queue(maxsize=capacity)``. ``submit`` uses ``put_nowait`` and raises
``Busy`` (-> 503 + Retry-After) when the queue is full. This is the Python equivalent of the Java
ThreadPoolTaskExecutor + AbortPolicy: fail fast instead of (a) blocking the request handler (CallerRuns) or
(b) silently dropping work (Discard). Workers bound concurrent LLM calls, which matters because the LLM is
rate-limited. ``submit`` snapshots ``contextvars`` (user id) and runs the job inside that context, so the worker
sees the caller's identity and nothing leaks between jobs.
"""
from __future__ import annotations

import asyncio
import contextvars
import logging
from typing import Awaitable, Callable

from app.errors import Busy

log = logging.getLogger(__name__)
JobFn = Callable[[], Awaitable[object]]


class BoundedTaskQueue:
    def __init__(self, workers: int, capacity: int):
        self._workers_n = workers
        self._queue: asyncio.Queue[tuple[contextvars.Context, JobFn, asyncio.Future]] = asyncio.Queue(maxsize=capacity)
        self._workers: list[asyncio.Task] = []

    def start(self) -> None:
        self._workers = [asyncio.create_task(self._run(i), name=f"llm-{i}") for i in range(self._workers_n)]

    async def stop(self) -> None:
        for w in self._workers:
            w.cancel()
        await asyncio.gather(*self._workers, return_exceptions=True)

    def submit(self, fn: JobFn) -> asyncio.Future:
        fut: asyncio.Future = asyncio.get_running_loop().create_future()
        try:
            self._queue.put_nowait((contextvars.copy_context(), fn, fut))
        except asyncio.QueueFull:
            raise Busy("Server is busy, please retry shortly") from None
        return fut

    async def _run(self, idx: int) -> None:
        while True:
            ctx, fn, fut = await self._queue.get()
            try:
                # run the coroutine in the caller's copied context (user id propagation)
                result = await asyncio.create_task(fn(), context=ctx)
                if not fut.done():
                    fut.set_result(result)
            except asyncio.CancelledError:
                raise
            except Exception as e:  # job errors are recorded by the job itself; keep the worker alive
                log.warning("Background job failed: %s", e)
                if not fut.done():
                    fut.set_exception(e)
                    fut.exception()  # mark retrieved
            finally:
                self._queue.task_done()
