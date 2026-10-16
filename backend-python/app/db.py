"""Async psycopg pool + tiny Flyway-style migrator (db/migration/V<n>__*.sql, applied in order, files read-only)."""
from __future__ import annotations

import logging
import re
from pathlib import Path

from psycopg_pool import AsyncConnectionPool

log = logging.getLogger(__name__)
MIGRATIONS = Path(__file__).resolve().parent.parent / "db" / "migration"
_NAME = re.compile(r"^V(\d+)__(.+)\.sql$")


def migration_files() -> list[tuple[int, Path]]:
    files = []
    for p in MIGRATIONS.iterdir():
        m = _NAME.match(p.name)
        if m:
            files.append((int(m.group(1)), p))
    return sorted(files)


async def migrate(pool: AsyncConnectionPool) -> None:
    async with pool.connection() as conn:
        await conn.execute("""CREATE TABLE IF NOT EXISTS schema_history (
            version INT PRIMARY KEY, name TEXT NOT NULL, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())""")
        cur = await conn.execute("SELECT version FROM schema_history")
        done = {r[0] for r in await cur.fetchall()}
        for version, path in migration_files():
            if version in done:
                continue
            log.info("Applying migration %s", path.name)
            async with conn.transaction():
                await conn.execute(path.read_text())
                await conn.execute("INSERT INTO schema_history (version, name) VALUES (%s, %s)", (version, path.name))
