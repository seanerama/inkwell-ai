"""Health check (unauthenticated) — the deploy smoke target."""

from __future__ import annotations

from fastapi import APIRouter

from app import __version__
from app.api.errors import CONTRACT_VALUE
from app.api.schemas import HealthOut

router = APIRouter()


@router.get("/health", response_model=HealthOut)
def health() -> HealthOut:
    return HealthOut(status="ok", version=__version__, contract=CONTRACT_VALUE)
