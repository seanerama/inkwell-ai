"""Stage 25 migration 0006: a pre-0006 row is backfilled with a hash and the generated
``search`` column is populated so a full-text query matches (ADR-0013 §1).

Downgrades to 0005, inserts a row through the old schema, upgrades to 0006, and asserts
the backfill. Leaves the schema at head for the rest of the suite.
"""

from __future__ import annotations

import uuid

import pytest
from sqlalchemy import text

from app.brain.store import compute_hash
from app.db.base import get_engine


@pytest.fixture
def _restore_head():
    yield
    from alembic import command
    from alembic.config import Config

    command.upgrade(Config("alembic.ini"), "head")


def test_pre_0006_row_gets_hash_and_search(_restore_head):
    from alembic import command
    from alembic.config import Config

    cfg = Config("alembic.ini")
    engine = get_engine()

    # Roll back just the Stage 25 migration, then insert a row through the OLD schema
    # (no hash/search columns).
    command.downgrade(cfg, "0005")
    row_id = uuid.uuid4()
    body = "The quarterly budget review is scheduled for October."
    with engine.begin() as conn:
        conn.execute(
            text(
                "INSERT INTO brain_entries (id, space_slug, kind, text, tags) "
                "VALUES (:id, 'work', 'fact', :text, ARRAY['finance']::varchar[])"
            ),
            {"id": row_id, "text": body},
        )

    # Apply 0006: the Python backfill fills hash; the generated column fills search.
    command.upgrade(cfg, "0006")

    with engine.begin() as conn:
        got_hash = conn.execute(
            text("SELECT hash FROM brain_entries WHERE id = :id"), {"id": row_id}
        ).scalar_one()
        assert got_hash == compute_hash(body)

        # A full-text query over the generated column matches the backfilled row.
        matched = conn.execute(
            text(
                "SELECT id FROM brain_entries "
                "WHERE search @@ websearch_to_tsquery('english', 'budget') AND id = :id"
            ),
            {"id": row_id},
        ).scalar_one_or_none()
        assert matched is not None

        # Clean up the row we inserted so the shared table is empty for the next test.
        conn.execute(text("DELETE FROM brain_entries WHERE id = :id"), {"id": row_id})
