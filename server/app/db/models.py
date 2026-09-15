"""SQLAlchemy ORM models (SPEC §4 + ADR-0003 queue columns + ADR-0008 token columns).

Enum-like columns are stored as VARCHAR with a CHECK constraint (``native_enum=False``)
rather than as Postgres ENUM types, so additive vocabulary changes stay plain
``ALTER``s and never require a type migration.
"""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import (
    ARRAY,
    Boolean,
    CheckConstraint,
    DateTime,
    Enum,
    Float,
    ForeignKey,
    Integer,
    String,
    Text,
    func,
)
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base

DIRECTIONS = ("to_agent", "to_user")
JOB_STATUSES = ("queued", "running", "done", "failed", "cancelled")
ORIGINS = ("user", "agent")
LAYER_OWNERS = ("user", "agent")
LAYER_TYPES = ("ink", "raster", "annotation")
CARD_KINDS = ("answer", "task", "fact", "question", "action", "error")
CARD_STATES = ("open", "done", "dismissed")
BRAIN_KINDS = ("fact", "task", "reference", "decision")


def _uuid() -> uuid.UUID:
    return uuid.uuid4()


def _pk() -> Mapped[uuid.UUID]:
    return mapped_column(UUID(as_uuid=True), primary_key=True, default=_uuid)


class Space(Base):
    __tablename__ = "spaces"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=_uuid)
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    slug: Mapped[str] = mapped_column(String(64), nullable=False, unique=True)
    system_prompt: Mapped[str] = mapped_column(Text, nullable=False, default="")
    tools: Mapped[list[str]] = mapped_column(ARRAY(String), nullable=False, default=list)
    model: Mapped[str] = mapped_column(String(64), nullable=False, default="claude-sonnet-5")
    color: Mapped[str] = mapped_column(String(16), nullable=False, default="#000000")
    position: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class Canvas(Base):
    __tablename__ = "canvases"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=_uuid)
    space_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("spaces.id"), nullable=False
    )
    title: Mapped[str] = mapped_column(String(255), nullable=False, default="")
    width_cu: Mapped[int] = mapped_column(Integer, nullable=False, default=2480)
    height_cu: Mapped[int] = mapped_column(Integer, nullable=False, default=3508)
    origin: Mapped[str] = mapped_column(
        Enum(*ORIGINS, name="canvas_origin", native_enum=False), nullable=False, default="user"
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now(), onupdate=func.now()
    )


class Layer(Base):
    __tablename__ = "layers"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=_uuid)
    canvas_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("canvases.id"), nullable=False
    )
    z: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    owner: Mapped[str] = mapped_column(
        Enum(*LAYER_OWNERS, name="layer_owner", native_enum=False), nullable=False
    )
    type: Mapped[str] = mapped_column(
        Enum(*LAYER_TYPES, name="layer_type", native_enum=False), nullable=False
    )
    visible: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    opacity: Mapped[float] = mapped_column(Float, nullable=False, default=1.0)
    job_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class Raster(Base):
    __tablename__ = "rasters"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=_uuid)
    layer_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("layers.id"), nullable=False
    )
    blob_uri: Mapped[str] = mapped_column(String(512), nullable=False)
    mime: Mapped[str] = mapped_column(String(64), nullable=False)
    page: Mapped[int | None] = mapped_column(Integer, nullable=True)
    x_cu: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    y_cu: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    w_cu: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)
    h_cu: Mapped[float] = mapped_column(Float, nullable=False, default=0.0)


class Job(Base):
    __tablename__ = "jobs"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=_uuid)
    space_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("spaces.id"), nullable=False
    )
    canvas_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    direction: Mapped[str] = mapped_column(
        Enum(*DIRECTIONS, name="job_direction", native_enum=False), nullable=False
    )
    type: Mapped[str] = mapped_column(String(64), nullable=False)
    status: Mapped[str] = mapped_column(
        Enum(*JOB_STATUSES, name="job_status", native_enum=False),
        nullable=False,
        default="queued",
        index=True,
    )
    request: Mapped[dict] = mapped_column(JSONB, nullable=False, default=dict)
    result: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    error: Mapped[str | None] = mapped_column(Text, nullable=True)
    # ADR-0003: Postgres-backed queue bookkeeping.
    locked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    locked_by: Mapped[str | None] = mapped_column(String(128), nullable=True)
    lease_expires_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    cancel_requested: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    # Stage 5 token accounting (migration 0002; nullable — set only for agent jobs).
    input_tokens: Mapped[int | None] = mapped_column(Integer, nullable=True)
    output_tokens: Mapped[int | None] = mapped_column(Integer, nullable=True)
    coordinate_clamps: Mapped[int | None] = mapped_column(Integer, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now(), onupdate=func.now()
    )

    cards: Mapped[list[Card]] = relationship(back_populates="job")


class Card(Base):
    __tablename__ = "cards"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=_uuid)
    job_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("jobs.id"), nullable=False
    )
    kind: Mapped[str] = mapped_column(
        Enum(*CARD_KINDS, name="card_kind", native_enum=False), nullable=False
    )
    title: Mapped[str] = mapped_column(String(255), nullable=False)
    body: Mapped[str] = mapped_column(Text, nullable=False, default="")
    anchors: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    actions: Mapped[list] = mapped_column(JSONB, nullable=False, default=list)
    state: Mapped[str] = mapped_column(
        Enum(*CARD_STATES, name="card_state", native_enum=False), nullable=False, default="open"
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )

    job: Mapped[Job] = relationship(back_populates="cards")


class BrainEntry(Base):
    __tablename__ = "brain_entries"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=_uuid)
    space_slug: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    kind: Mapped[str] = mapped_column(
        Enum(*BRAIN_KINDS, name="brain_kind", native_enum=False), nullable=False
    )
    text: Mapped[str] = mapped_column(Text, nullable=False)
    tags: Mapped[list[str]] = mapped_column(ARRAY(String), nullable=False, default=list)
    source_canvas_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    source_region: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class DeviceToken(Base):
    __tablename__ = "device_tokens"
    __table_args__ = (CheckConstraint("length(token_hash) > 0", name="token_hash_nonempty"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=_uuid)
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    token_hash: Mapped[str] = mapped_column(String(128), nullable=False, unique=True, index=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
