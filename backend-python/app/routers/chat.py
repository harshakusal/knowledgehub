from __future__ import annotations

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse

from app.deps import chat_service, container, current_user_id, rag_service
from app.schemas import ChatRequest, RagAnswer
from app.services.chat_stream_service import ChatStreamService
from app.services.rag_service import RagService

router = APIRouter(prefix="/api/chat")


@router.post("/stream")
async def stream(body: ChatRequest, request: Request, user: str = Depends(current_user_id),
                 chat: ChatStreamService = Depends(chat_service)) -> StreamingResponse:
    c = container(request)
    # fail fast with a real HTTP 404 before the stream opens if any requested document isn't the user's
    await c.permissions.require_all_readable(body.document_ids, user)
    c.streams.acquire()

    async def body_iter():
        try:
            async for chunk in chat.stream(user, body.question, body.document_ids, request.is_disconnected):
                yield chunk
        finally:
            c.streams.release()

    return StreamingResponse(body_iter(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@router.post("/answer", response_model=RagAnswer)
async def answer(body: ChatRequest, user: str = Depends(current_user_id), rag: RagService = Depends(rag_service)):
    # async I/O end-to-end: awaiting the LLM never blocks the event loop
    return await rag.answer(user, body.question, body.document_ids)
