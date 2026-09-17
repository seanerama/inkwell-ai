"""Request/response models for contract ``device-api`` (SPEC §4 entity shapes)."""

from __future__ import annotations

import re
import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field

SLUG_PATTERN = r"^[a-z0-9-]{1,64}$"
COLOR_PATTERN = r"^#[0-9a-fA-F]{6}$"


def slugify(name: str) -> str:
    """Derive a slug from a name: lowercase, non-alnum → ``-``, collapse/trim, ≤64."""
    s = re.sub(r"[^a-z0-9]+", "-", name.lower())
    s = re.sub(r"-+", "-", s).strip("-")
    return s[:64].strip("-")


class SpaceOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    name: str
    slug: str
    system_prompt: str
    tools: list[str]
    model: str
    color: str
    position: int
    created_at: datetime


class SpaceCreate(BaseModel):
    """Body for ``POST /spaces`` (Stage 13 additive to contract device-api)."""

    name: str = Field(min_length=1, max_length=120)
    slug: str | None = Field(default=None, pattern=SLUG_PATTERN)
    system_prompt: str = Field(default="", max_length=8000)
    tools: list[str] = Field(default_factory=list)
    model: str = Field(default="claude-sonnet-5", min_length=1, max_length=64)
    color: str = Field(default="#000000", pattern=COLOR_PATTERN)
    position: int | None = None


class SpaceUpdate(BaseModel):
    """Body for ``PATCH /spaces/{id}`` — every field optional (``exclude_unset``).

    ``slug`` is declared so a client attempt to change it can be detected and rejected
    (slug is immutable); it is never applied.
    """

    name: str | None = Field(default=None, min_length=1, max_length=120)
    slug: str | None = None
    system_prompt: str | None = Field(default=None, max_length=8000)
    tools: list[str] | None = None
    model: str | None = Field(default=None, min_length=1, max_length=64)
    color: str | None = Field(default=None, pattern=COLOR_PATTERN)
    position: int | None = None


class JobCreate(BaseModel):
    type: str
    space_id: uuid.UUID | None = None
    canvas_id: uuid.UUID | None = None
    image: str | None = None
    export: dict | None = None
    instruction: str | None = None
    selection: list[float] | None = None
    meta: dict | None = None


class CanvasOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    space_id: uuid.UUID
    title: str
    width_cu: int
    height_cu: int
    origin: str
    created_at: datetime


class CardOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    kind: str
    title: str
    body: str
    anchors: list
    actions: list
    state: str
    created_at: datetime


class JobOut(BaseModel):
    id: uuid.UUID
    space_id: uuid.UUID
    canvas_id: uuid.UUID | None
    direction: str
    type: str
    status: str
    request: dict
    result: dict | None
    error: str | None
    created_at: datetime
    updated_at: datetime
    cards: list[CardOut] = []


class SyncOut(BaseModel):
    jobs: list[JobOut]
    cursor: str


class HealthOut(BaseModel):
    status: str
    version: str
    contract: str


def job_to_out(job) -> JobOut:
    # ADR-0004: never return the base64 image; expose image_key in its place.
    request = dict(job.request or {})
    request.pop("image", None)
    cards = [CardOut.model_validate(card) for card in sorted(job.cards, key=lambda c: c.created_at)]
    return JobOut(
        id=job.id,
        space_id=job.space_id,
        canvas_id=job.canvas_id,
        direction=job.direction,
        type=job.type,
        status=job.status,
        request=request,
        result=job.result,
        error=job.error,
        created_at=job.created_at,
        updated_at=job.updated_at,
        cards=cards,
    )
