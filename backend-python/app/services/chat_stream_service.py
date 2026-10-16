"""SSE answer stream. Events (same contract as the Java backend):
   event: sources  data: [{"documentId":..,"chunkIndex":..}]
   event: token    data: {"t": "..."}
   event: error    data: {"message": "..."}
   event: done     data: {}
Stops pulling from the LLM as soon as the client disconnects."""
from __future__ import annotations

import json
import logging
from typing import AsyncIterator, Awaitable, Callable
from uuid import UUID

from app.clients.llm_client import LlmClient
from app.errors import LlmUnavailable, NotFound
from app.models import RetrievedChunk
from app.services.prompt_builder import PromptBuilder
from app.services.retrieval_service import RetrievalService

log = logging.getLogger(__name__)


def sse(event: str, data) -> str:
    return f"event: {event}\ndata: {json.dumps(data, separators=(',', ':'))}\n\n"


class ChatStreamService:
    def __init__(self, retrieval: RetrievalService, prompts: PromptBuilder, llm: LlmClient):
        self._retrieval, self._prompts, self._llm = retrieval, prompts, llm

    async def stream(self, user_id: str, question: str, document_ids: list[UUID],
                     is_disconnected: Callable[[], Awaitable[bool]]) -> AsyncIterator[str]:
        try:
            context: list[RetrievedChunk] = await self._retrieval.retrieve(user_id, question, document_ids)
            yield sse("sources", [{"documentId": str(c.document_id), "chunkIndex": c.chunk_index} for c in context])
            if not context:
                yield sse("token", {"t": "I couldn't find anything relevant in your documents."})
            else:
                async for token in self._llm.stream_chat(self._prompts.streaming_prompt(question, context), is_disconnected):
                    if await is_disconnected():
                        log.info("Client disconnected; stream stopped")
                        return
                    yield sse("token", {"t": token})
                if await is_disconnected():
                    log.info("Client disconnected; stream stopped")
                    return
            yield sse("done", {})
        except (LlmUnavailable, NotFound) as e:
            yield sse("error", {"message": e.detail})
        except Exception as e:  # never leak internals to the client
            log.warning("Chat stream failed: %s", e)
            yield sse("error", {"message": "Something went wrong while generating the answer."})
