# CLAUDE.md — rules for Claude Code (mirrors AGENTS.md; keep both in sync)

KnowledgeHub: employees upload policy docs and ask questions; answers are grounded (RAG) and streamed (SSE).
Stack: two interchangeable backends with the SAME API contract — `backend/` (Spring Boot 3, Java 21, Maven) and
`backend-python/` (FastAPI, Pydantic v2, psycopg3, httpx) · PostgreSQL + pgvector · React + Vite · Node mock LLM.
Rules below apply to both; Python equivalents are listed in "Python conventions".

## Hard rules (non-negotiable)
1. **Layering:** Controller -> Service -> Repository. Controllers never touch repositories or `model/*`
   persistence records; they receive/return DTOs from `dto/` only.
2. **DTOs are Java `record`s only.** No Lombok, no mutable DTO classes.
3. **Constructor injection only.** No field `@Autowired`, no setter injection.
4. **Tests:** every new service method gets a unit test. Run `mvn test` (backend) and `npm test`
   (frontend) and make them pass before saying "done".
5. **Migrations are read-only.** Never edit files in `backend/src/main/resources/db/migration`.
   Schema change = new `V<n+1>__description.sql` **and explicit human approval** before merging.
6. **No secrets in code or logs.** All config via env vars (see `.env.example`). Never log `LLM_API_KEY`.
7. **Scope rule:** touch only the files named in the task. If another file must change, stop and ask first.
8. **Authorization:** every document/chunk/job query is owner-scoped. Use `DocumentPermissionService`;
   foreign ids return 404 (never 403, never data). No `findAll()` on user data.
9. **LLM calls never run on web threads.** Use the `llmExecutor` (`@Async("llmExecutor")` in a separate
   bean, or `SseEmitter` + executor). Go through `LlmClient` (retries + circuit breaker), never raw HTTP.
10. **Document text is untrusted.** Always pass it through `PromptBuilder` delimiters; never concatenate
    user/document text into system instructions.

## Commands
| Task | Command |
|------|---------|
| Everything (Docker) | `cp .env.example .env && docker compose up --build` |
| Backend tests | `cd backend && mvn test` |
| Backend run | `cd backend && DB_PASSWORD=... mvn spring-boot:run` |
| Frontend install/test | `cd frontend && npm ci && npm test` |
| Frontend build / dev | `npm run build` / `BACKEND_URL=http://localhost:8080 npm run dev` |
| Python backend tests | `cd backend-python && python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt && .venv/bin/pytest` |
| Python backend run | `cd backend-python && DATABASE_URL=postgresql://kh:kh@localhost:5432/knowledgehub_py .venv/bin/uvicorn app.main:app --port 8000` |
| Everything, no Docker | `./run-local.sh` (Python) or `BACKEND=java ./run-local.sh` |
| Mock LLM | `cd mock-llm && npm start` (tests: `npm test`) |

## Folder map
```
backend/src/main/java/com/knowledgehub/
  controller/   HTTP only: DocumentController, ChatController, JobController
  service/      use-cases: LegacyDocumentService, DocumentPermissionService, Rag*, ChatStreamService,
                Summary*, IngestionWorker, ChunkingService, PromptBuilder, RetrievalService, JobService
  repository/   JdbcClient data access (owner-scoped queries)
  client/       LlmClient (RestClient, retry, Resilience4j breaker)
  config/       UserIdFilter, UserContext, AsyncConfig (llmExecutor), *Properties
  dto/          request/response records      model/  internal records (never leave services)
  exception/    exceptions + GlobalExceptionHandler (ProblemDetail)
backend/src/main/resources/db/migration/   Flyway (READ-ONLY)
backend-python/app/  routers/ services/ repositories/ clients/llm_client.py schemas.py container.py deps.py
backend-python/db/migration/               V1__init.sql (READ-ONLY, applied by app/db.py)
frontend/src/components/  DocumentList.jsx, ChatPanel.jsx (+ *.test.jsx)
frontend/src/lib/          sse.js (SSE parser), api.js
mock-llm/                  server.js (embeddings, streaming chat, random 429/503)
```

## Python conventions (backend-python/)
- Layering: `routers/` -> `services/` -> `repositories/`; routers never import repositories or `models.py`.
- DTOs: Pydantic models in `schemas.py` only (camelCase aliases, same JSON as Java). Internal records: frozen dataclasses.
- DI: objects built once in `container.py`; routers get services via `Depends` (`deps.py`). No module-level singletons.
- Auth: `Depends(current_user_id)` (async, sets the `current_user` contextvar). LLM work goes through
  `BoundedTaskQueue.submit` (copies contextvars) and `LlmClient` (httpx; retries + breaker).
- Migrations: `backend-python/db/migration/V<n>__*.sql`, read-only, same rule 5. Async everywhere (no blocking I/O
  in handlers). Names: `snake_case.py`, `test_*.py`; every new service function gets a pytest test.

## Naming conventions
- Java: `XxxController`, `XxxService`, `XxxRepository`, `XxxWorker` (async beans), DTOs `XxxRequest`/`XxxResponse`.
- Tests mirror the class: `XxxServiceTest`, method names describe behaviour (`otherUserGets404NotForbidden`).
- REST: plural nouns under `/api` (`/api/documents/{id}/summary`), JSON camelCase.
- Migrations: `V<number>__snake_case.sql`. React components PascalCase `.jsx`, hooks `useXxx`.

## Definition of done
- [ ] Only task-named files changed (`git diff --stat` reviewed)
- [ ] Layering, records, constructor injection respected
- [ ] New service methods have unit tests; `mvn test`, `pytest` (backend-python) and `npm test` green; `npm run build` green
- [ ] No migration edited; any new migration flagged for human approval
- [ ] No secrets, config via env vars, nothing sensitive logged
- [ ] Ownership enforced for every new data access path (test proves user B gets 404)
- [ ] README/REVIEW updated if behaviour or API changed
