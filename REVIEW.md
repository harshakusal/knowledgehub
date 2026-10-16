# REVIEW.md — code review of the original AI-generated code

Originals are preserved verbatim in `docs/original/`. Severity: **Critical** = exploitable data leak or outage,
**High** = security/correctness bug likely in normal use, **Medium** = robustness/maintainability, **Low** = style/API hygiene.

## 2.1 `DocumentController` (backend)

| # | Problem | Severity | Fix in rewrite |
|---|---------|----------|----------------|
| 1 | **IDOR / no ownership checks**: `GET /{id}`, `summary`, `DELETE` accept any id; any user reads/deletes anyone's document. Sequential `Long` ids make enumeration trivial. | Critical | `DocumentPermissionService.requireReadable(id, user)`; UUID ids; foreign ids → **404** (not 403, so existence isn't leaked). |
| 2 | **`findAll()` leaks every user's documents** (including full content). | Critical | `DocumentRepository.findByOwner(owner, q, limit)`; there is no `findAll`. |
| 3 | **Prompt injection via `style`**: request param concatenated straight into the prompt next to document text; document text itself also unfenced. | High | `style` removed; `PromptBuilder` puts fixed instructions in the system message, wraps docs in `<<<DOCUMENT>>>` delimiters, neutralises delimiter look-alikes. |
| 4 | **Delete has no auth and returns a string**: `"deleted"` with 200 even when nothing existed; anyone can delete. | High | Owner check, `204 No Content`, 404 if not found/not owned. |
| 5 | **Synchronous LLM call on the Tomcat thread**: a slow or rate-limited LLM pins request threads → whole app freezes under load. | High | `POST /{id}/summary` → **202 + jobId**; work runs on bounded `llmExecutor` via `@Async` in `SummaryWorker`; `GET /api/jobs/{id}`. |
| 6 | **`Optional.get()`** → `NoSuchElementException` → **500** for a missing id (and a stack trace in logs). | Medium | `orElseThrow(NotFoundException)` mapped by `GlobalExceptionHandler` to RFC-7807 404. |
| 7 | **Entity exposure**: JPA entity used as request *and* response body → mass assignment (client can set `id`, `ownerId`), lazy-loading/serialisation surprises, schema leaks into API. | Medium | Multipart upload; responses are `DocumentResponse`/`DocumentDetailResponse` records; `ownerId` comes from auth, never the body. |
| 8 | **No validation**: unbounded `style`, any body, any file type/size. | Medium | Bean Validation (`@Size`, `@NotBlank`, `@Valid`), UTF-8 + extension + size checks, `MaxUploadSizeExceeded` → 413. |
| 9 | **Field `@Autowired` injection**: hidden deps, untestable without reflection, allows partially built beans. | Low | Constructor injection, `final` fields. |
| 10 | Controller talks to the repository directly (no service layer, no transaction boundary). | Low | Controller → `LegacyDocumentService` / `SummaryService` → repositories. |
| 11 | No error contract; LLM errors bubble as 500. | Low | ProblemDetail everywhere; `LlmUnavailableException`/`TaskRejectedException` → 503 + `Retry-After`. |

**Auth model (stub, documented):** `UserIdFilter` requires `X-User-Id` (`^[A-Za-z0-9._-]{1,64}$`) on `/api/**`
(else 401) and stores it in `UserContext` (ThreadLocal, copied to async threads by `UserContextTaskDecorator`).
In production replace the filter with an OIDC resource server / gateway; nothing else changes because all
code depends only on `UserContext`. The header is trivially spoofable — it is an assessment stub, not security.

Tests: `DocumentControllerTest` (401 without header, DTO-only JSON, 404 not 500, 400 malformed id, 201/204/202),
`DocumentPermissionServiceTest`, `LegacyDocumentServiceTest`, `SummaryServiceTest`.

## 2.3 `DocumentList.jsx` (frontend)

| # | Bug | Severity | Why it breaks | Fix |
|---|-----|----------|---------------|-----|
| 1 | `dangerouslySetInnerHTML={{__html: d.title}}` | Critical | Stored **XSS**: a title like `<img src=x onerror=…>` runs script in every viewer's session. | Render `{d.title}` as text (React escapes). Test asserts no `<img>` is created. |
| 2 | `useEffect(() => setQuery(query.trim()))` with **no deps** | High | Runs after every render; any state change re-renders → effect → setState… infinite loop / jank, and it fights the user's typing. | Removed. Trim happens when deriving the debounced value. |
| 3 | `setInterval` **never cleared** | High | Every mount (and StrictMode double-mount) leaks an interval that keeps fetching after unmount → memory leak + "state update on unmounted component". | Interval only while some doc is `PENDING`, `clearInterval` in cleanup. Test spies on `clearInterval`. |
| 4 | **Stale closure** `setDocs(docs.concat(data))` inside interval created with `[]` deps | High | `docs` is frozen at `[]`… and concat appends the full list every 3 s → duplicates grow forever. | Poll triggers a reload that **replaces** the list. Test asserts single occurrence. |
| 5 | `useEffect(async () => …)` | Medium | Effect returns a Promise instead of a cleanup function (React warns; no way to cancel). | Inner `async function load()`, effect returns `() => controller.abort()`. |
| 6 | **Race conditions** | Medium | Typing "po" → requests for "p" and "po"; if "p" resolves last, stale results overwrite fresh ones. | `AbortController` per request + 300 ms debounce. Test resolves the stale request late and asserts it's ignored. |
| 7 | **Unencoded query params** `'?q=' + query` | Medium | `&`, `#`, `=` break the URL / inject extra params (e.g. `q=x&user=bob`). | `encodeURIComponent`. Test checks `a&b=c` → `a%26b%3Dc`. |
| 8 | `&user=' + userId` in the URL | Medium | Identity chosen by the client in a query string (spoofable, logged in proxies). | Identity sent via `X-User-Id` header (stub auth); server ignores any `user` param. |
| 9 | **Index keys** `key={i}` | Low | Reordering/deleting reuses DOM/state of the wrong row (checkbox state jumps). | `key={d.id}`. |
| 10 | No error/loading handling, `res.ok` unchecked | Low | A 500/401 JSON body is put into `docs` and `.map` crashes. | `res.ok` check, error + loading UI. |

## 2.2 Streaming (design notes)
- Backend `POST /api/chat/stream` returns `SseEmitter`; the work runs on `llmExecutor`, events `sources`, `token`
  (`{"t": "..."}` JSON so whitespace survives SSE framing), `error`, `done`.
- Disconnect: failed `emitter.send` / `onError` / `onCompletion` / `onTimeout` set an `AtomicBoolean`; `LlmClient.streamChat`
  checks it between upstream lines and closes the upstream connection (mock-llm logs
  `client disconnected after N/M tokens` — verified manually).
- `ChatPanel`: `fetch` + `body.getReader()` + `TextDecoder({stream:true})` + incremental parser (`lib/sse.js`, tested
  with char-by-char chunking); Stop = `AbortController.abort()`; states idle/streaming/done/stopped/error; input disabled
  while streaming; abort on unmount.
