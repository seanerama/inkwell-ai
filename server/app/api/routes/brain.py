"""Brain routes (Stage 25 — the three frozen ``/brain/{space_slug}`` routes, ADR-0013).

Full-text search over ``brain_entries`` (Postgres ``websearch_to_tsquery`` ranked by
``ts_rank_cd``, ties by recency; Stage 30: a multi-term ``q`` with no AND match falls
back to OR matching — ``app.brain.store.search_brain``), idempotent create, and soft
delete. All three are
device-token only and gated by the ``BRAIN_ENABLED`` kill-switch: when off they return
``403`` with ``error.code="disabled"``. Soft-deleted rows are never returned.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime
from typing import Literal

from fastapi import APIRouter, Depends, Query, Response
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.deps import get_db, require_token
from app.api.errors import ApiError
from app.api.schemas import BrainEntryOut
from app.brain.store import create_entry, search_brain
from app.config import get_settings
from app.db.models import BrainEntry, DeviceToken, Space

router = APIRouter()

_LIMIT_DEFAULT = 50
_LIMIT_MAX = 200


class BrainCreate(BaseModel):
    kind: Literal["fact", "task", "reference", "decision"]
    text: str = Field(min_length=1, max_length=2000)
    tags: list[str] = Field(default_factory=list)
    source_canvas_id: uuid.UUID | None = None


def _require_enabled() -> None:
    if not get_settings().brain_enabled:
        raise ApiError(403, "disabled", "the brain is disabled")


def _require_space(db: Session, space_slug: str) -> Space:
    space = db.execute(select(Space).where(Space.slug == space_slug)).scalar_one_or_none()
    if space is None:
        raise ApiError(404, "not_found", f"unknown space {space_slug!r}")
    return space


@router.get("/brain/{space_slug}", response_model=list[BrainEntryOut])
def list_brain(
    space_slug: str,
    q: str | None = None,
    limit: int = Query(default=_LIMIT_DEFAULT),
    db: Session = Depends(get_db),
    _: DeviceToken = Depends(require_token),
) -> list[BrainEntryOut]:
    _require_enabled()
    _require_space(db, space_slug)
    limit = max(1, min(limit, _LIMIT_MAX))

    # The shared search (AND first, OR fallback for a multi-term q with no AND hit).
    rows = search_brain(db, space_slug, q, limit=limit).entries
    return [BrainEntryOut.model_validate(r) for r in rows]


@router.post("/brain/{space_slug}", response_model=BrainEntryOut, status_code=201)
def create_brain(
    space_slug: str,
    body: BrainCreate,
    response: Response,
    db: Session = Depends(get_db),
    _: DeviceToken = Depends(require_token),
) -> BrainEntryOut:
    _require_enabled()
    _require_space(db, space_slug)

    entry, created = create_entry(
        db,
        space_slug=space_slug,
        kind=body.kind,
        text=body.text,
        tags=body.tags,
        source_canvas_id=body.source_canvas_id,
    )
    db.commit()
    db.refresh(entry)
    # Idempotent: a duplicate of a live entry returns 200 with the existing row.
    response.status_code = 201 if created else 200
    return BrainEntryOut.model_validate(entry)


@router.delete("/brain/{space_slug}/{entry_id}", status_code=204)
def delete_brain(
    space_slug: str,
    entry_id: uuid.UUID,
    db: Session = Depends(get_db),
    _: DeviceToken = Depends(require_token),
) -> Response:
    _require_enabled()
    entry = db.execute(
        select(BrainEntry)
        .where(BrainEntry.id == entry_id)
        .where(BrainEntry.space_slug == space_slug)
        .where(BrainEntry.deleted_at.is_(None))
    ).scalar_one_or_none()
    if entry is None:
        raise ApiError(404, "not_found", "unknown brain entry")
    entry.deleted_at = datetime.now(UTC)
    db.commit()
    return Response(status_code=204)
