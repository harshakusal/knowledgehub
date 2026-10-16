"""RAG with a validated JSON contract. Invalid output (bad JSON, schema violation, citation not in the retrieved
set, answerFound without citations) -> one corrective retry -> safe fallback RagAnswer.not_found()."""
from __future__ import annotations

import logging
import re
from uuid import UUID

from pydantic import ValidationError

from app.clients.llm_client import LlmClient
from app.models import RetrievedChunk
from app.schemas import RagAnswer
from app.services.prompt_builder import PromptBuilder
from app.services.retrieval_service import RetrievalService

log = logging.getLogger(__name__)


class RagService:
    def __init__(self, retrieval: RetrievalService, prompts: PromptBuilder, llm: LlmClient):
        self._retrieval, self._prompts, self._llm = retrieval, prompts, llm

    async def answer(self, user_id: str, question: str, document_ids: list[UUID]) -> RagAnswer:
        context = await self._retrieval.retrieve(user_id, question, document_ids)
        if not context:
            return RagAnswer.not_found()
        messages = self._prompts.json_prompt(question, context)
        raw = await self._llm.complete(messages, json_mode=True)
        parsed = self.validate(raw, context)
        if parsed:
            return parsed
        log.info("Invalid structured answer from LLM; retrying once")
        messages = messages + [
            {"role": "assistant", "content": raw},
            {"role": "user", "content": "Your previous reply was invalid. Reply ONLY with the JSON object described "
                                        "in the rules, citing only the provided documentId/chunkIndex pairs."}]
        parsed = self.validate(await self._llm.complete(messages, json_mode=True), context)
        if parsed:
            return parsed
        log.warning("Structured answer invalid after retry; returning fallback")
        return RagAnswer.not_found()

    @staticmethod
    def validate(raw: str | None, context: list[RetrievedChunk]) -> RagAnswer | None:
        if not raw:
            return None
        text = re.sub(r"\s*```$", "", re.sub(r"^```(?:json)?\s*", "", raw.strip()))
        try:
            a = RagAnswer.model_validate_json(text)
        except ValidationError:
            return None
        if not a.answer.strip():
            return None
        allowed = {(c.document_id, c.chunk_index) for c in context}
        if any((c.document_id, c.chunk_index) not in allowed for c in a.citations):
            return None
        if a.answer_found and not a.citations:
            return None
        return a
