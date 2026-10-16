"""Characterization tests for LegacyDocumentService access rules (Q1.2). Written against the pre-refactor code and
kept unchanged through the DocumentPermissionService extraction."""
import uuid
from datetime import datetime, timezone

import pytest

from app.errors import NotFound
from app.models import Document
from app.services.legacy_document_service import LegacyDocumentService
from tests.fakes import FakeDocumentRepository

NOW = datetime(2026, 1, 1, tzinfo=timezone.utc)


@pytest.fixture
async def setup():
    repo = FakeDocumentRepository()
    alice = Document(uuid.uuid4(), "alice", "Leave", "20 days", "READY", NOW)
    bob = Document(uuid.uuid4(), "bob", "Bob notes", "secret", "READY", NOW)
    await repo.insert(alice)
    await repo.insert(bob)
    return LegacyDocumentService(repo, None, None), repo, alice, bob


async def test_owner_can_get(setup):
    svc, _, alice, _ = setup
    r = await svc.get(alice.id, "alice")
    assert (r.id, r.title, r.content) == (alice.id, "Leave", "20 days")


async def test_other_user_get_is_not_found(setup):
    svc, _, alice, _ = setup
    with pytest.raises(NotFound, match="Document not found"):
        await svc.get(alice.id, "bob")


async def test_missing_document_get_is_not_found(setup):
    svc, *_ = setup
    with pytest.raises(NotFound, match="Document not found"):
        await svc.get(uuid.uuid4(), "alice")


async def test_missing_user_is_rejected(setup):
    svc, _, alice, _ = setup
    with pytest.raises(NotFound):
        await svc.get(alice.id, None)
    with pytest.raises(NotFound):
        await svc.delete(alice.id, None)
    with pytest.raises(NotFound):
        await svc.require_readable(alice.id, None)


async def test_owner_can_delete(setup):
    svc, repo, alice, _ = setup
    await svc.delete(alice.id, "alice")
    assert repo.deleted == [alice.id]


async def test_other_user_delete_is_not_found_and_deletes_nothing(setup):
    svc, repo, alice, _ = setup
    with pytest.raises(NotFound, match="Document not found"):
        await svc.delete(alice.id, "bob")
    assert repo.deleted == [] and alice.id in repo.docs


async def test_require_readable_returns_owned_document(setup):
    svc, _, alice, _ = setup
    assert await svc.require_readable(alice.id, "alice") == alice
    with pytest.raises(NotFound):
        await svc.require_readable(alice.id, "bob")


async def test_require_all_readable_returns_ids_in_order_or_rejects_any_foreign(setup):
    svc, _, alice, bob = setup
    assert await svc.require_all_readable([alice.id], "alice") == [alice.id]
    assert await svc.require_all_readable([], "alice") == []
    with pytest.raises(NotFound):
        await svc.require_all_readable([alice.id, bob.id], "alice")
    with pytest.raises(NotFound):
        await svc.require_all_readable([uuid.uuid4()], "alice")


async def test_list_only_returns_callers_documents(setup):
    svc, _, alice, bob = setup
    assert [d.id for d in await svc.list("alice", "")] == [alice.id]
    assert [d.id for d in await svc.list("bob", None)] == [bob.id]
