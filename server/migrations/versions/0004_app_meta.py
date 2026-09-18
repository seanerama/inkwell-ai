"""app_meta key/value table: pepper-generation marker for the rotation grace window

Revision ID: 0004
Revises: 0003
Create Date: 2026-09-17

Additive only (framework-spec §4.3, ADR-0008 Stage 20): a tiny key/value table holding
server-side operational state. It stores ``pepper_generation`` (a stringified int) so
``inkwell token migrate-check`` can fail closed against an explicit target generation
instead of guessing MAX(hash_version). A MISSING row means generation 1 in code, so this
migration adds no seed row and existing installs keep working. Downgrade drops the table.
"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "0004"
down_revision: Union[str, None] = "0003"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        "app_meta",
        sa.Column("key", sa.String(length=64), nullable=False),
        sa.Column("value", sa.String(length=255), nullable=False),
        sa.PrimaryKeyConstraint("key"),
    )


def downgrade() -> None:
    op.drop_table("app_meta")
