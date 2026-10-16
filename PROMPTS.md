# PROMPTS.md — AI-assisted refactor: extracting `DocumentPermissionService`

Goal: move the ad-hoc ownership checks scattered through `LegacyDocumentService` (and duplicated in the original
controller) into one `DocumentPermissionService`, **without changing observable behaviour**.

## 1. Context & scoping prompt
> You are working in KnowledgeHub. Read AGENTS.md first and follow it. Task: extract permission logic from
> `service/LegacyDocumentService.java` into a new `service/DocumentPermissionService.java`.
> **You may only touch:** `LegacyDocumentService.java`, `DocumentPermissionService.java` (new),
> `LegacyDocumentServiceTest.java`, `DocumentPermissionServiceTest.java` (new). Ask before touching anything else.
> Do NOT edit migrations, controllers, or repositories. Do not write code yet.

Why: explicit file allow-list keeps the diff reviewable and prevents "helpful" drive-by edits.

## 2. Plan-before-edit prompt
> List every place in `LegacyDocumentService` that decides whether a user may see/modify a document. For each,
> state the current behaviour (status/exception for missing doc, foreign doc, null user). Then propose the new
> API of `DocumentPermissionService` and the exact call-site changes. Wait for my approval.

Agreed plan: `requireReadable(UUID, String) -> Document` (404 for missing **and** foreign),
`canRead(Document, String)`, `requireAllReadable(Collection<UUID>, String)` for chat/RAG scoping.
`get` and `delete` delegate; `list` stays owner-scoped at the query level (a permission check per row would be N+1).

## 3. Behaviour protection: characterization tests first
> Before refactoring, write characterization tests for `LegacyDocumentService` that pin current behaviour:
> upload stores owner/title/PENDING and triggers ingestion; validation errors; list is owner-scoped;
> get/delete of another user's document throw NotFoundException and never call delete; owner can delete.
> Run `mvn test` and show me they pass on the UNCHANGED code.

Result: `LegacyDocumentServiceTest` (7 tests) green before the change. Then:

> Now perform the extraction exactly as planned. Keep `LegacyDocumentServiceTest` unchanged except for the
> constructor wiring. Add `DocumentPermissionServiceTest`. Run `mvn test`.

## 4. Verification
```
$ git diff --stat   (refactor step, as recorded during the session; the repo history squashes it into the backend commit)
 .../service/DocumentPermissionService.java       | 41 +++++++++++
 .../service/LegacyDocumentService.java           | 21 ++---
 .../service/LegacyDocumentServiceTest.java       |  3 +-
 .../service/DocumentPermissionServiceTest.java   | 53 ++++++++++++++
```
Checked: only allow-listed files; characterization tests unchanged in assertions; `mvn test` green.

## 5. Rejected suggestions (and why)
| AI suggestion | Decision | Reason |
|---|---|---|
| Rename `LegacyDocumentService` → `DocumentService` in the same change | Rejected | Out of scope; touches controller + tests; do it as a separate, mechanical PR. |
| Return **403** for foreign documents | Rejected | Leaks existence of other users' ids; 404 is the agreed contract (tests pin it). |
| Add `owner_id` index via editing `V1__init.sql` | Rejected | Migrations are read-only (AGENTS.md rule 5); index already exists; any change needs `V2__` + approval. |
| Use Spring Security `@PreAuthorize("@perm.canRead(#id)")` | Rejected (for now) | Would pull in Spring Security & SpEL for a stub-auth assessment; explicit call is easier to test/read. |
| Per-row permission filter in `list()` | Rejected | N+1 queries; owner predicate in SQL is both faster and safer. |
| Catch-all `try/catch (Exception)` returning `null` in `requireReadable` | Rejected | Hides failures and turns them into NPEs later. |
| "While I'm here" reformatting of `ChunkingService` | Rejected | Not in allow-list. |
