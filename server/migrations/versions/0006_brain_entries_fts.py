"""brain_entries: provenance, soft delete, dedupe hash, and a full-text search column

Revision ID: 0006
Revises: 0005
Create Date: 2026-09-21

Additive only (framework-spec §4.3, ADR-0013 §1 Stage 25). Adds to ``brain_entries``:

- ``job_id`` UUID NULL — the to_agent job whose brain_writes produced the row.
- ``deleted_at`` TIMESTAMPTZ NULL — soft delete; the routes never return a deleted row.
- ``hash`` VARCHAR(64) NOT NULL — sha256(lower(collapse_ws(text))). Added NULLABLE,
  backfilled in Python (byte-identical to app/brain/store.compute_hash so the partial
  unique index stays consistent with runtime), then set NOT NULL.
- a generated ``search`` TSVECTOR STORED column (text weight A, tags weight B) + a GIN
  index on it, and a partial UNIQUE index on ``(space_slug, hash) WHERE deleted_at IS
  NULL`` for live-entry dedupe.

Downgrade drops all of the above in reverse; the migration-check gate runs the whole
``downgrade base && upgrade head`` cycle on a real Postgres.
"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import UUID

# Reuse the runtime hash so backfilled rows match hashes computed by the store.
from app.brain.store import compute_hash

revision: str = "0006"
down_revision: Union[str, None] = "0005"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

# The generated ``search`` column: text weight A, tags weight B (ADR-0013 §1). A STORED
# generated column requires an IMMUTABLE expression, but Postgres marks ``array_to_string``
# (and the ``text[]::text`` cast) STABLE, so the ADR's inline expression is rejected with
# "generation expression is not immutable". The equivalent immutable SQL wrapper below is
# the standard escape hatch: it computes exactly the ADR's tsvector and is safe to mark
# IMMUTABLE (a plain space-join + English to_tsvector is deterministic). The generated
# column references the wrapper so the expression itself is a single immutable call.
_SEARCH_FN_DDL = (
    "CREATE FUNCTION brain_search_vector(p_text text, p_tags text[]) "
    "RETURNS tsvector LANGUAGE sql IMMUTABLE AS $fn$ "
    "SELECT setweight(to_tsvector('english'::regconfig, coalesce(p_text, '')), 'A') "
    "|| setweight(to_tsvector('english'::regconfig, "
    "coalesce(array_to_string(p_tags, ' '), '')), 'B') $fn$"
)
_SEARCH_DDL = (
    "ALTER TABLE brain_entries ADD COLUMN search tsvector "
    "GENERATED ALWAYS AS (brain_search_vector(text, tags)) STORED"
)


def upgrade() -> None:
    op.add_column("brain_entries", sa.Column("job_id", UUID(), nullable=True))
    op.add_column(
        "brain_entries", sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True)
    )
    # hash: add NULLABLE, backfill in Python, then enforce NOT NULL.
    op.add_column("brain_entries", sa.Column("hash", sa.String(length=64), nullable=True))

    conn = op.get_bind()
    rows = conn.execute(sa.text("SELECT id, text FROM brain_entries")).fetchall()
    for row in rows:
        conn.execute(
            sa.text("UPDATE brain_entries SET hash = :h WHERE id = :id"),
            {"h": compute_hash(row.text), "id": row.id},
        )
    op.alter_column("brain_entries", "hash", nullable=False)

    # Generated full-text search column + its GIN index (DB-only; not in the ORM).
    op.execute(_SEARCH_FN_DDL)
    op.execute(_SEARCH_DDL)
    op.execute("CREATE INDEX ix_brain_entries_search ON brain_entries USING gin (search)")

    # Live-entry dedupe: at most one non-deleted row per (space_slug, hash).
    op.execute(
        "CREATE UNIQUE INDEX uq_brain_entries_slug_hash "
        "ON brain_entries (space_slug, hash) WHERE deleted_at IS NULL"
    )


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS uq_brain_entries_slug_hash")
    op.execute("DROP INDEX IF EXISTS ix_brain_entries_search")
    op.execute("ALTER TABLE brain_entries DROP COLUMN IF EXISTS search")
    op.execute("DROP FUNCTION IF EXISTS brain_search_vector(text, text[])")
    op.drop_column("brain_entries", "hash")
    op.drop_column("brain_entries", "deleted_at")
    op.drop_column("brain_entries", "job_id")
