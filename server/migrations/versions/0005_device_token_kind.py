"""device token kind: device vs agent tokens for the Stage 23 push API

Revision ID: 0005
Revises: 0004
Create Date: 2026-09-18

Additive only (framework-spec §4.3, ADR-0012 Stage 23): one NOT NULL VARCHAR(16) column
on ``device_tokens`` recording whether a token is a ``device`` token (device routes) or
an ``agent`` token (the push API). The server default ``device`` backfills every existing
row, so the column is safe to add to a live table without a rewrite. Downgrade drops it.
"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "0005"
down_revision: Union[str, None] = "0004"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "device_tokens",
        sa.Column("kind", sa.String(length=16), nullable=False, server_default="device"),
    )


def downgrade() -> None:
    op.drop_column("device_tokens", "kind")
