# PROMPTS.md — Q1.2: extracting `DocumentPermissionService` with an AI coding agent

> **Provenance:** this session was executed by an AI coding agent (**Grok Bot**) on **2026-10-16** (IST) against the
> Python backend (`backend-python/`). Every prompt, command output and diff stat below is copied from the real run;
> the step-by-step raw log is in [`docs/q12-session-log.md`](docs/q12-session-log.md).
> Git history shows it: branch `q12-legacy-baseline` (the "before" state) → branch `q12-refactor` (3 commits) → merged
> into `main`.
>
> An earlier version of this file described the refactor as a narrative that had not actually been recorded. It has been
> replaced. The Java backend (`backend/`) already had `DocumentPermissionService` when it was first written; no
> recorded refactor session exists for it.

## 0. Setting up an honest "before" state
The Python service had been written with the permission service already extracted, so it was first put back into its
legacy shape (commit `44db76e`, branch `q12-legacy-baseline`): the ownership predicate
`doc is None or user_id is None or doc.owner_id != user_id → NotFound("Document not found")` was written inline
**four times** in `LegacyDocumentService` (`get`, `delete`, `require_readable`, `require_all_readable`), and
`document_permission_service.py` plus its test were deleted. `pytest`: 59 passed.

## 1. Scoping (file allow-list)
Prompt acted on:
> plan-only step: read CLAUDE.md, inspect the legacy file, write the plan (files allowed: legacy_document_service.py,
> new document_permission_service.py, their tests) with NO edits

The relevant CLAUDE.md rules were read with `grep` (constructor injection only; every new service method gets a test;
migrations read-only; **scope rule 7: touch only files named in the task, ask before others**).
Allowed: `app/services/legacy_document_service.py`, `app/services/document_permission_service.py` (new),
`tests/test_legacy_document_service.py` (new), `tests/test_document_permission_service.py` (new).

## 2. Plan before edit
Written with zero edits (`git status --short | wc -l` → `0`):
1. New `DocumentPermissionService(documents)` with `can_read`, `require_readable`, `require_all_readable`, the same
   semantics (404 for a missing document, someone else's document, or no user).
2. Constructor injection into `LegacyDocumentService` as a new **last** parameter
   `permissions: DocumentPermissionService | None = None` (defaults to `DocumentPermissionService(documents)`).
   Why: `container.py` and existing tests build `LegacyDocumentService(documents, ingestion, queue)` positionally;
   requiring the new argument would force edits outside the allow-list.
3. Keep `LegacyDocumentService.require_readable/require_all_readable` as thin delegates, so callers in
   `retrieval_service.py`, `summary_service.py` and `routers/chat.py` (found with `grep`) stay untouched.
4. Characterization tests first, green on unchanged code, committed; then refactor without editing them.
5. Explicitly deferred: rewiring callers/container to the new service, renaming `LegacyDocumentService`.

## 3. Behaviour protection — characterization tests
Prompt acted on:
> write characterization tests pinning current behaviour (owner read/delete ok, other user not-found, missing user
> rejected, etc.), run pytest, capture real pass output; commit

`tests/test_legacy_document_service.py`, 9 tests, run against the **unchanged** legacy code:
```
test_owner_can_get PASSED
test_other_user_get_is_not_found PASSED
test_missing_document_get_is_not_found PASSED
test_missing_user_is_rejected PASSED
test_owner_can_delete PASSED
test_other_user_delete_is_not_found_and_deletes_nothing PASSED
test_require_readable_returns_owned_document PASSED
test_require_all_readable_returns_ids_in_order_or_rejects_any_foreign PASSED
test_list_only_returns_callers_documents PASSED
============================== 9 passed in 0.14s ===============================
full suite: 68 passed in 0.69s
```
Committed as `7960983 test: characterization tests for LegacyDocumentService access rules (Q1.2)`.

## 4. The extraction
Prompt acted on:
> do the extraction with constructor injection, no behaviour change, only allowed files; run pytest + npm test,
> capture real output

New `document_permission_service.py`; `LegacyDocumentService` delegates from `get`, `delete`, `require_readable`,
`require_all_readable` (4 duplicated blocks removed); new `tests/test_document_permission_service.py` (4 tests,
including a spy proving the injected instance is used). The characterization test file was **not modified**
(`git diff --stat 7960983..HEAD -- tests/test_legacy_document_service.py` is empty).
```
pytest -q                     → 72 passed in 0.77s
characterization + new tests  → 13 passed in 0.13s
frontend npx vitest run       → Test Files 3 passed (3), Tests 12 passed (12)
```

## 5. Self-review and rejected changes (only what actually happened)
Prompt acted on:
> review your own diff; if anything was outside scope or unnecessary (e.g. renames, reformatting), revert it and record
> the rejection with reason

| Change found in my own diff | Decision | Reason |
|---|---|---|
| Module docstring rewritten to add "(extracted in Q1.2, see PROMPTS.md)" | **Rejected** (reverted) | Unnecessary doc churn / cross-reference in code. After reverting, the original sentence ("access rules are written inline in each method") was false, so only a minimal accurate sentence was kept: "Access rules are delegated to DocumentPermissionService." |
| `permissions or DocumentPermissionService(documents)` | **Changed** to `if permissions is not None else …` | A truthiness check would silently replace an injected object that evaluates falsy. In scope, behaviour-neutral. |
| Rewire `container.py`, retrieval, summary, chat router to the new service; rename `LegacyDocumentService` | **Not done** (considered, deferred) | Outside the file allow-list (scope rule 7); needs approval as a follow-up. |

No renames or reformatting happened, so none are listed. Post-review: `pytest` 72 passed, characterization 9 passed,
`npm test` 12 passed. Committed as
`51bb48e refactor: extract DocumentPermissionService from LegacyDocumentService (constructor-injected, no behaviour change)`.

## 6. Real `git diff --stat q12-legacy-baseline..HEAD` (code commits)
```
 .../app/services/document_permission_service.py    | 32 ++++++++
 .../app/services/legacy_document_service.py        | 28 +++----
 .../tests/test_document_permission_service.py      | 62 +++++++++++++++
 .../tests/test_legacy_document_service.py          | 87 ++++++++++++++++++++++
 4 files changed, 190 insertions(+), 19 deletions(-)
```
Only the four allow-listed files changed.

## 7. Takeaways
- Writing the plan before any edit surfaced the outside callers early, which led to the "keep delegates + optional
  constructor argument" design that kept the change inside the allow-list.
- Characterization tests committed *before* the refactor make "no behaviour change" checkable, not just claimed.
- Reviewing my own diff caught one unnecessary edit and one latent bug (`or` vs `is not None`).
