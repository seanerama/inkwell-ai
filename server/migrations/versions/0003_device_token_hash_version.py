"""device token hash_version: pepper-generation marker for the rotation grace window

Revision ID: 0003
Revises: 0002
Create Date: 2026-09-17

Additive only (framework-spec §4.3, ADR-0008 Stage 18): one NOT NULL integer column on
``device_tokens`` recording which pepper generation the stored hash was computed under.
The server default ``1`` backfills every existing row, so the column is safe to add to a
live table. verify_token bumps it to 2 when it re-hashes a row from the previous pepper
to the current one during a dual-pepper grace window. Downgrade drops it.
"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "0003"
down_revision: Union[str, None] = "0002"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "device_tokens",
        sa.Column("hash_version", sa.Integer(), nullable=False, server_default="1"),
    )


def downgrade() -> None:
    op.drop_column("device_tokens", "hash_version")
