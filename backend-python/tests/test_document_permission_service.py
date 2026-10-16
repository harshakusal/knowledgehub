import uuid
from datetime import datetime, timezone

import pytest

from app.errors import NotFound
from app.models import Document
from app.services.document_permission_service import DocumentPermissionService
from app.services.legacy_document_service import LegacyDocumentService
from tests.fakes import FakeDocumentRepository

NOW = datetime(2026, 1, 1, tzinfo=timezone.utc)


@pytest.fixture
async def env():
    repo = FakeDocumentRepository()
    alice = Document(uuid.uuid4(), "alice", "Leave", "20 days", "READY", NOW)
    await repo.insert(alice)
    return DocumentPermissionService(repo), repo, alice


def test_can_read_only_owner(env):
    perms, _, alice = env
    assert perms.can_read(alice, "alice")
    assert not perms.can_read(alice, "bob")
    assert not perms.can_read(alice, None)


async def test_require_readable(env):
    perms, _, alice = env
    assert await perms.require_readable(alice.id, "alice") == alice
    for user, doc_id in [("bob", alice.id), (None, alice.id), ("alice", uuid.uuid4())]:
        with pytest.raises(NotFound, match="Document not found"):
            await perms.require_readable(doc_id, user)


async def test_require_all_readable(env):
    perms, repo, alice = env
    other = Document(uuid.uuid4(), "bob", "B", "x", "READY", NOW)
    await repo.insert(other)
    assert await perms.require_all_readable([alice.id], "alice") == [alice.id]
    assert await perms.require_all_readable(iter([]), "alice") == []
    with pytest.raises(NotFound):
        await perms.require_all_readable([alice.id, other.id], "alice")


async def test_legacy_service_uses_injected_permission_service(env):
    _, repo, alice = env

    class Spy(DocumentPermissionService):
        calls = []

        async def require_readable(self, document_id, user_id):
            self.calls.append((document_id, user_id))
            return await super().require_readable(document_id, user_id)

    spy = Spy(repo)
    svc = LegacyDocumentService(repo, None, None, permissions=spy)
    await svc.get(alice.id, "alice")
    await svc.delete(alice.id, "alice")
    assert spy.calls == [(alice.id, "alice"), (alice.id, "alice")]
