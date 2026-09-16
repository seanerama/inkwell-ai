"""Request/response models for contract ``device-api`` (SPEC §4 entity shapes)."""

from __future__ import annotations

import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict


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
