"""job token accounting: input_tokens, output_tokens, coordinate_clamps on jobs

Revision ID: 0002
Revises: 0001
Create Date: 2026-09-15

Additive only (framework-spec §4.3, SPEC §10.5): three NULLABLE integer columns on
``jobs`` for per-job token usage and the coordinate-clamp metric. They are set only for
agent jobs; every existing and non-agent job keeps NULL. Downgrade drops them.
"""

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "0002"
down_revision: Union[str, None] = "0001"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column("jobs", sa.Column("input_tokens", sa.Integer(), nullable=True))
    op.add_column("jobs", sa.Column("output_tokens", sa.Integer(), nullable=True))
    op.add_column("jobs", sa.Column("coordinate_clamps", sa.Integer(), nullable=True))


def downgrade() -> None:
    op.drop_column("jobs", "coordinate_clamps")
    op.drop_column("jobs", "output_tokens")
    op.drop_column("jobs", "input_tokens")
