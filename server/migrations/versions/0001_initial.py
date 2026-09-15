"""initial schema: spaces, canvases, layers, rasters, jobs, cards, brain_entries, device_tokens

Revision ID: 0001
Revises:
Create Date: 2026-09-15

SPEC §4 entities plus ADR-0003 queue columns (locked_at, locked_by, lease_expires_at,
cancel_requested, attempts) and ADR-0008 token columns (token_hash, revoked_at,
last_seen_at). Enum-like columns are VARCHAR + CHECK (native_enum=False) so vocabulary
growth stays additive.
"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0001"
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

UUID = postgresql.UUID(as_uuid=True)
JSONB = postgresql.JSONB
TS = sa.DateTime(timezone=True)


def _enum(*values: str, name: str) -> sa.Enum:
    return sa.Enum(*values, name=name, native_enum=False)


def upgrade() -> None:
    op.create_table(
        "spaces",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("name", sa.String(120), nullable=False),
        sa.Column("slug", sa.String(64), nullable=False, unique=True),
        sa.Column("system_prompt", sa.Text(), nullable=False, server_default=""),
        sa.Column("tools", postgresql.ARRAY(sa.String()), nullable=False, server_default="{}"),
        sa.Column("model", sa.String(64), nullable=False, server_default="claude-sonnet-5"),
        sa.Column("color", sa.String(16), nullable=False, server_default="#000000"),
        sa.Column("position", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("created_at", TS, nullable=False, server_default=sa.func.now()),
    )

    op.create_table(
        "canvases",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("space_id", UUID, sa.ForeignKey("spaces.id"), nullable=False),
        sa.Column("title", sa.String(255), nullable=False, server_default=""),
        sa.Column("width_cu", sa.Integer(), nullable=False, server_default="2480"),
        sa.Column("height_cu", sa.Integer(), nullable=False, server_default="3508"),
        sa.Column("origin", _enum("user", "agent", name="canvas_origin"), nullable=False,
                  server_default="user"),
        sa.Column("created_at", TS, nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", TS, nullable=False, server_default=sa.func.now()),
    )

    op.create_table(
        "layers",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("canvas_id", UUID, sa.ForeignKey("canvases.id"), nullable=False),
        sa.Column("z", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("owner", _enum("user", "agent", name="layer_owner"), nullable=False),
        sa.Column("type", _enum("ink", "raster", "annotation", name="layer_type"), nullable=False),
        sa.Column("visible", sa.Boolean(), nullable=False, server_default=sa.true()),
        sa.Column("opacity", sa.Float(), nullable=False, server_default="1.0"),
        sa.Column("job_id", UUID, nullable=True),
        sa.Column("created_at", TS, nullable=False, server_default=sa.func.now()),
    )

    op.create_table(
        "rasters",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("layer_id", UUID, sa.ForeignKey("layers.id"), nullable=False),
        sa.Column("blob_uri", sa.String(512), nullable=False),
        sa.Column("mime", sa.String(64), nullable=False),
        sa.Column("page", sa.Integer(), nullable=True),
        sa.Column("x_cu", sa.Float(), nullable=False, server_default="0"),
        sa.Column("y_cu", sa.Float(), nullable=False, server_default="0"),
        sa.Column("w_cu", sa.Float(), nullable=False, server_default="0"),
        sa.Column("h_cu", sa.Float(), nullable=False, server_default="0"),
    )

    op.create_table(
        "jobs",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("space_id", UUID, sa.ForeignKey("spaces.id"), nullable=False),
        sa.Column("canvas_id", UUID, nullable=True),
        sa.Column("direction", _enum("to_agent", "to_user", name="job_direction"), nullable=False),
        sa.Column("type", sa.String(64), nullable=False),
        sa.Column("status",
                  _enum("queued", "running", "done", "failed", "cancelled", name="job_status"),
                  nullable=False, server_default="queued"),
        sa.Column("request", JSONB, nullable=False, server_default="{}"),
        sa.Column("result", JSONB, nullable=True),
        sa.Column("error", sa.Text(), nullable=True),
        # ADR-0003 queue bookkeeping
        sa.Column("locked_at", TS, nullable=True),
        sa.Column("locked_by", sa.String(128), nullable=True),
        sa.Column("lease_expires_at", TS, nullable=True),
        sa.Column("cancel_requested", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("attempts", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("created_at", TS, nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", TS, nullable=False, server_default=sa.func.now()),
    )
    op.create_index("ix_jobs_status", "jobs", ["status"])
    op.create_index("ix_jobs_updated_at_id", "jobs", ["updated_at", "id"])

    op.create_table(
        "cards",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("job_id", UUID, sa.ForeignKey("jobs.id"), nullable=False),
        sa.Column("kind",
                  _enum("answer", "task", "fact", "question", "action", "error", name="card_kind"),
                  nullable=False),
        sa.Column("title", sa.String(255), nullable=False),
        sa.Column("body", sa.Text(), nullable=False, server_default=""),
        sa.Column("anchors", JSONB, nullable=False, server_default="[]"),
        sa.Column("actions", JSONB, nullable=False, server_default="[]"),
        sa.Column("state", _enum("open", "done", "dismissed", name="card_state"),
                  nullable=False, server_default="open"),
        sa.Column("created_at", TS, nullable=False, server_default=sa.func.now()),
    )

    op.create_table(
        "brain_entries",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("space_slug", sa.String(64), nullable=False),
        sa.Column("kind", _enum("fact", "task", "reference", "decision", name="brain_kind"),
                  nullable=False),
        sa.Column("text", sa.Text(), nullable=False),
        sa.Column("tags", postgresql.ARRAY(sa.String()), nullable=False, server_default="{}"),
        sa.Column("source_canvas_id", UUID, nullable=True),
        sa.Column("source_region", JSONB, nullable=True),
        sa.Column("created_at", TS, nullable=False, server_default=sa.func.now()),
    )
    op.create_index("ix_brain_entries_space_slug", "brain_entries", ["space_slug"])

    op.create_table(
        "device_tokens",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("name", sa.String(120), nullable=False),
        sa.Column("token_hash", sa.String(128), nullable=False, unique=True),
        sa.Column("created_at", TS, nullable=False, server_default=sa.func.now()),
        sa.Column("last_seen_at", TS, nullable=True),
        sa.Column("revoked_at", TS, nullable=True),
        sa.CheckConstraint("length(token_hash) > 0", name="token_hash_nonempty"),
    )
    op.create_index("ix_device_tokens_token_hash", "device_tokens", ["token_hash"])


def downgrade() -> None:
    op.drop_table("device_tokens")
    op.drop_index("ix_brain_entries_space_slug", table_name="brain_entries")
    op.drop_table("brain_entries")
    op.drop_table("cards")
    op.drop_index("ix_jobs_updated_at_id", table_name="jobs")
    op.drop_index("ix_jobs_status", table_name="jobs")
    op.drop_table("jobs")
    op.drop_table("rasters")
    op.drop_table("layers")
    op.drop_table("canvases")
    op.drop_table("spaces")
