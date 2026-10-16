from __future__ import annotations

from uuid import UUID

from fastapi import APIRouter, Depends, File, Form, Query, Response, UploadFile, status

from app.deps import current_user_id, document_service, summary_service
from app.errors import BadRequest
from app.schemas import DocumentDetailResponse, DocumentResponse, JobResponse
from app.services.legacy_document_service import LegacyDocumentService
from app.services.summary_service import SummaryService

router = APIRouter(prefix="/api")
MAX_UPLOAD = 5 * 1024 * 1024


@router.get("/documents", response_model=list[DocumentResponse], response_model_by_alias=True)
async def list_documents(q: str | None = Query(default=None, max_length=100),
                         user: str = Depends(current_user_id),
                         docs: LegacyDocumentService = Depends(document_service)):
    return await docs.list(user, q)


@router.get("/documents/{doc_id}", response_model=DocumentDetailResponse)
async def get_document(doc_id: UUID, user: str = Depends(current_user_id),
                       docs: LegacyDocumentService = Depends(document_service)):
    return await docs.get(doc_id, user)


@router.post("/documents", status_code=status.HTTP_201_CREATED, response_model=DocumentResponse)
async def upload(response: Response, file: UploadFile = File(...), title: str | None = Form(default=None, max_length=255),
                 user: str = Depends(current_user_id), docs: LegacyDocumentService = Depends(document_service)):
    data = await file.read(MAX_UPLOAD + 1)
    if len(data) > MAX_UPLOAD:
        raise BadRequest("File too large")
    created = await docs.upload(user, title, file.filename, data)
    response.headers["Location"] = f"/api/documents/{created.id}"
    return created


@router.delete("/documents/{doc_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_document(doc_id: UUID, user: str = Depends(current_user_id),
                          docs: LegacyDocumentService = Depends(document_service)) -> Response:
    await docs.delete(doc_id, user)
    return Response(status_code=204)


@router.post("/documents/{doc_id}/summary", status_code=status.HTTP_202_ACCEPTED, response_model=JobResponse)
async def summarize(doc_id: UUID, response: Response, user: str = Depends(current_user_id),
                    summaries: SummaryService = Depends(summary_service)):
    job = await summaries.request_summary(doc_id, user)
    response.headers["Location"] = f"/api/jobs/{job.job_id}"
    return job


@router.get("/jobs/{job_id}", response_model=JobResponse)
async def get_job(job_id: UUID, user: str = Depends(current_user_id),
                  summaries: SummaryService = Depends(summary_service)):
    return summaries.get_job(job_id, user)
