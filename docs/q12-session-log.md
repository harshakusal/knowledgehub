# Q1.2 session log — DocumentPermissionService extraction (Python backend)

Executed by an AI coding agent (Grok Bot) on 2026-10-16 (IST) in `/workspace/knowledgehub`.
Base: branch `q12-legacy-baseline` (commit 44db76e, "chore: restore pre-refactor legacy service baseline for Q1.2").
Work branch: `q12-refactor`. Outputs below are pasted from the actual commands (trimmed only where marked).

---
## Step (a) — plan only, no edits

**Instruction acted on (verbatim from the task):**
> plan-only step: read CLAUDE.md, inspect the legacy file, write the plan (files allowed: legacy_document_service.py,
> new document_permission_service.py, their tests) with NO edits

**Commands run and real output:**
```
$ grep -nE "Scope rule|Constructor injection|every new service|Migrations are" CLAUDE.md
12:3. **Constructor injection only.** No field `@Autowired`, no setter injection.
13:4. **Tests:** every new service method gets a unit test. Run `mvn test` (backend) and `npm test`
15:5. **Migrations are read-only.** Never edit files in `backend/src/main/resources/db/migration`.
18:7. **Scope rule:** touch only the files named in the task. If another file must change, stop and ask first.
66:  in handlers). Names: `snake_case.py`, `test_*.py`; every new service function gets a pytest test.

$ grep -n "owner_id != user_id" backend-python/app/services/legacy_document_service.py
60:        if doc is None or user_id is None or doc.owner_id != user_id:
66:        if doc is None or user_id is None or doc.owner_id != user_id:
74:        if doc is None or user_id is None or doc.owner_id != user_id:
82:            if doc is None or user_id is None or doc.owner_id != user_id:

$ grep -rn "require_readable\|require_all_readable" backend-python/app --include=*.py | grep -v legacy_document_service
backend-python/app/routers/chat.py:19:    await c.permissions.require_all_readable(body.document_ids, user)
backend-python/app/services/retrieval_service.py:17:        scope = await self._permissions.require_all_readable(document_ids, user_id)
backend-python/app/services/summary_service.py:26:            doc = await self._permissions.require_readable(document_id, require_user())
backend-python/app/services/summary_service.py:41:        await self._permissions.require_readable(document_id, user_id)

$ git status --short | wc -l
0          # nothing edited during planning
```

**Findings:**
- The same ownership predicate `doc is None or user_id is None or doc.owner_id != user_id -> NotFound("Document not found")`
  is duplicated 4x in `LegacyDocumentService` (`get`, `delete`, `require_readable`, `require_all_readable`).
- External callers use only the two public methods `require_readable` / `require_all_readable`
  (retrieval_service.py, summary_service.py, routers/chat.py via `container.py`).

**Plan (written before any edit):**
1. Allowed files only: `app/services/legacy_document_service.py`, new `app/services/document_permission_service.py`,
   new `tests/test_legacy_document_service.py` (characterization), new `tests/test_document_permission_service.py`.
2. New `DocumentPermissionService(documents)` with `can_read(doc, user_id)`, `require_readable(id, user_id) -> Document`,
   `require_all_readable(ids, user_id) -> list[UUID]`; identical semantics (404 for missing, foreign, or `None` user).
3. `LegacyDocumentService.__init__` gains a constructor-injected `permissions: DocumentPermissionService | None = None`
   as the LAST parameter, defaulting to `DocumentPermissionService(documents)`. Reason: `container.py` and existing tests
   construct `LegacyDocumentService(documents, ingestion, queue)` positionally; changing that wiring would touch files
   outside the allow-list (scope rule 7). With a default, no other file has to change.
4. `get`/`delete` call `self._permissions.require_readable`; the legacy public `require_readable`/`require_all_readable`
   stay and delegate, so retrieval/summary/chat callers keep working unchanged.
5. Behaviour protection: write characterization tests first, run them green on the unchanged code, commit, then refactor
   without editing those tests.
6. Out of scope (follow-up, needs approval): point `container.py`, retrieval, summary and chat router at
   `DocumentPermissionService` directly; rename `LegacyDocumentService`.

---
## Step (b) — characterization tests on the UNCHANGED code

**Instruction acted on:**
> write characterization tests pinning current behaviour (owner read/delete ok, other user not-found, missing user
> rejected, etc.), run pytest, capture real pass output; commit

New file only: `backend-python/tests/test_legacy_document_service.py` (9 tests). Production code untouched.

```
$ .venv/bin/pytest tests/test_legacy_document_service.py -v
tests/test_legacy_document_service.py::test_owner_can_get PASSED         [ 11%]
tests/test_legacy_document_service.py::test_other_user_get_is_not_found PASSED [ 22%]
tests/test_legacy_document_service.py::test_missing_document_get_is_not_found PASSED [ 33%]
tests/test_legacy_document_service.py::test_missing_user_is_rejected PASSED [ 44%]
tests/test_legacy_document_service.py::test_owner_can_delete PASSED      [ 55%]
tests/test_legacy_document_service.py::test_other_user_delete_is_not_found_and_deletes_nothing PASSED [ 66%]
tests/test_legacy_document_service.py::test_require_readable_returns_owned_document PASSED [ 77%]
tests/test_legacy_document_service.py::test_require_all_readable_returns_ids_in_order_or_rejects_any_foreign PASSED [ 88%]
tests/test_legacy_document_service.py::test_list_only_returns_callers_documents PASSED [100%]
============================== 9 passed in 0.14s ===============================

$ .venv/bin/pytest -q
68 passed in 0.69s
```
Committed: `7960983 test: characterization tests for LegacyDocumentService access rules (Q1.2)`

---
## Step (c) — extraction (constructor injection, no behaviour change, allowed files only)

**Instruction acted on:**
> do the extraction with constructor injection, no behaviour change, only allowed files; run pytest + npm test,
> capture real output

Changes:
- New `app/services/document_permission_service.py`: `can_read`, `require_readable`, `require_all_readable`
  (same predicate and same `NotFound("Document not found")` as the four inline copies).
- `app/services/legacy_document_service.py`: new last constructor parameter
  `permissions: DocumentPermissionService | None = None`; `get`, `delete`, `require_readable`,
  `require_all_readable` now delegate. Four duplicated predicate blocks removed.
- New `tests/test_document_permission_service.py` (4 tests, incl. one proving the injected instance is used).
- `tests/test_legacy_document_service.py` NOT modified (verified: `git diff --stat 7960983..HEAD -- tests/test_legacy_document_service.py` is empty).

Real output after the edit (before review):
```
$ .venv/bin/pytest -q
72 passed in 0.77s
$ .venv/bin/pytest tests/test_legacy_document_service.py tests/test_document_permission_service.py -q
13 passed in 0.13s
$ (cd frontend && npx vitest run)
 Test Files  3 passed (3)
      Tests  12 passed (12)
```

---
## Step (d) — self-review of the diff

**Instruction acted on:**
> review your own diff; if anything was outside scope or unnecessary (e.g. renames, reformatting), revert it and record
> the rejection with reason - only record rejections that genuinely happened

`git status --short` showed only the allowed files (+ this log). Reviewing `git diff legacy_document_service.py` found:
1. **Module docstring rewritten** to "Access rules are delegated to DocumentPermissionService (extracted in Q1.2, see
   PROMPTS.md)." — I judged the cross-reference to PROMPTS.md unnecessary doc churn and **reverted the docstring to the
   original text**. Re-reading after the revert, the original sentence ("access rules are written inline in each
   method") was now *false*, so I re-applied the **minimal** correction only: "Access rules are delegated to
   DocumentPermissionService." Rejected part: the PROMPTS.md/Q1.2 cross-reference (not needed in code).
2. **`permissions or DocumentPermissionService(documents)`** — truthiness check would silently replace any injected
   object that evaluates falsy (e.g. a Mock configured with `__bool__`). Changed to
   `permissions if permissions is not None else DocumentPermissionService(documents)`. In-scope fix, not a rejection.
3. Checked for behaviour drift: same predicate (`None` user, missing doc, foreign owner -> 404), same message, same
   return values, `delete` still double-checks via `delete_by_id_and_owner`. No change.
4. Considered and **not done** (out of scope per plan step 6): rewiring `container.py`, `retrieval_service.py`,
   `summary_service.py`, `routers/chat.py` to use `DocumentPermissionService` directly, and renaming
   `LegacyDocumentService`. These would touch files outside the allow-list; left as a follow-up needing approval.

After review:
```
$ .venv/bin/pytest -q
72 passed in 0.70s
$ .venv/bin/pytest tests/test_legacy_document_service.py -q
9 passed in 0.13s
$ (cd frontend && npx vitest run)
      Tests  12 passed (12)
```
Committed: `51bb48e refactor: extract DocumentPermissionService from LegacyDocumentService (constructor-injected, no behaviour change)`

---
## Step (e) — real diff stat

```
$ git diff --stat q12-legacy-baseline..HEAD
 .../app/services/document_permission_service.py    | 32 ++++++++
 .../app/services/legacy_document_service.py        | 28 +++----
 .../tests/test_document_permission_service.py      | 62 +++++++++++++++
 .../tests/test_legacy_document_service.py          | 87 ++++++++++++++++++++++
 4 files changed, 190 insertions(+), 19 deletions(-)
```
(This log and the PROMPTS.md rewrite are committed afterwards and are not part of the code diff above.)
