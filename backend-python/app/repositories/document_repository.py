from __future__ import annotations

from uuid import UUID

from psycopg_pool import AsyncConnectionPool

from app.models import Document

_COLS = "id, owner_id, title, content, status, created_at"


def _row(r) -> Document:
    return Document(*r)


class DocumentRepository:
    """Every list query is owner-scoped; there is deliberately no find_all()."""

    def __init__(self, pool: AsyncConnectionPool):
        self._pool = pool

    async def insert(self, d: Document) -> None:
        async with self._pool.connection() as c:
            await c.execute(f"INSERT INTO documents ({_COLS}) VALUES (%s,%s,%s,%s,%s,%s)",
                            (d.id, d.owner_id, d.title, d.content, d.status, d.created_at))

    async def find_by_id(self, doc_id: UUID) -> Document | None:
        async with self._pool.connection() as c:
            cur = await c.execute(f"SELECT {_COLS} FROM documents WHERE id = %s", (doc_id,))
            r = await cur.fetchone()
            return _row(r) if r else None

    async def find_by_owner(self, owner_id: str, title_query: str, limit: int) -> list[Document]:
        like = "%" + title_query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        async with self._pool.connection() as c:
            cur = await c.execute(
                f"SELECT {_COLS} FROM documents WHERE owner_id = %s AND title ILIKE %s ORDER BY created_at DESC LIMIT %s",
                (owner_id, like, limit))
            return [_row(r) for r in await cur.fetchall()]

    async def update_status(self, doc_id: UUID, status: str) -> None:
        async with self._pool.connection() as c:
            await c.execute("UPDATE documents SET status = %s WHERE id = %s", (status, doc_id))

    async def delete_by_id_and_owner(self, doc_id: UUID, owner_id: str) -> bool:
        async with self._pool.connection() as c:
            cur = await c.execute("DELETE FROM documents WHERE id = %s AND owner_id = %s", (doc_id, owner_id))
            return cur.rowcount > 0
