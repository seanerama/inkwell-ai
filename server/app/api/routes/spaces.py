"""Spaces routes (SPEC §8).

``GET /spaces`` lists the spaces (always available). ``POST /spaces`` and
``PATCH /spaces/{id}`` create and edit spaces — the two routes the frozen device-api
route table already promises (Stage 13). Both require the bearer token and are gated by
the ``SPACES_EDITABLE`` kill-switch (``Settings.spaces_editable``, default OFF): when
off they return ``403`` with ``error.code="disabled"``. ``GET`` is unaffected.
"""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.api.deps import get_db, require_token
from app.api.errors import ApiError
from app.api.schemas import SpaceCreate, SpaceOut, SpaceUpdate, slugify
from app.config import get_settings
from app.db.models import DeviceToken, Space

router = APIRouter()


def _require_editable() -> None:
    if not get_settings().spaces_editable:
        raise ApiError(403, "disabled", "space editing is disabled")


@router.get("/spaces", response_model=list[SpaceOut])
def list_spaces(db: Session = Depends(get_db), _=Depends(require_token)) -> list[SpaceOut]:
    spaces = db.execute(select(Space).order_by(Space.position)).scalars().all()
    return [SpaceOut.model_validate(s) for s in spaces]


@router.post("/spaces", response_model=SpaceOut, status_code=201)
def create_space(
    body: SpaceCreate,
    db: Session = Depends(get_db),
    _: DeviceToken = Depends(require_token),
) -> SpaceOut:
    _require_editable()
    slug = body.slug or slugify(body.name)
    if not slug:
        raise ApiError(422, "validation", "could not derive a slug from name")
    if db.execute(select(Space).where(Space.slug == slug)).scalar_one_or_none() is not None:
        raise ApiError(409, "conflict", f"a space with slug {slug!r} already exists")
    if body.position is not None:
        position = body.position
    else:
        max_pos = db.execute(select(func.max(Space.position))).scalar()
        position = max_pos + 1 if max_pos is not None else 0
    space = Space(
        name=body.name,
        slug=slug,
        system_prompt=body.system_prompt,
        tools=list(body.tools),
        model=body.model,
        color=body.color,
        position=position,
    )
    db.add(space)
    db.commit()
    db.refresh(space)
    return SpaceOut.model_validate(space)


@router.patch("/spaces/{space_id}", response_model=SpaceOut)
def update_space(
    space_id: uuid.UUID,
    body: SpaceUpdate,
    db: Session = Depends(get_db),
    _: DeviceToken = Depends(require_token),
) -> SpaceOut:
    _require_editable()
    if body.slug is not None:
        raise ApiError(422, "validation", "slug is immutable")
    space = db.get(Space, space_id)
    if space is None:
        raise ApiError(404, "not_found", "unknown space id")
    updates = body.model_dump(exclude_unset=True)
    updates.pop("slug", None)
    for field, value in updates.items():
        setattr(space, field, value)
    db.commit()
    db.refresh(space)
    return SpaceOut.model_validate(space)
