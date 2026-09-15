"""Spaces routes (SPEC §8)."""

from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.deps import get_db, require_token
from app.api.schemas import SpaceOut
from app.db.models import Space

router = APIRouter()


@router.get("/spaces", response_model=list[SpaceOut])
def list_spaces(db: Session = Depends(get_db), _=Depends(require_token)) -> list[SpaceOut]:
    spaces = db.execute(select(Space).order_by(Space.position)).scalars().all()
    return [SpaceOut.model_validate(s) for s in spaces]
