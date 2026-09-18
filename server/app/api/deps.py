"""FastAPI dependencies: DB session and bearer-token auth (ADR-0008)."""

from __future__ import annotations

from collections.abc import Iterator

from fastapi import Depends, Request
from sqlalchemy.orm import Session

from app.api.errors import ApiError
from app.db.base import session_scope
from app.db.models import DeviceToken
from app.security.tokens import verify_token


def get_db() -> Iterator[Session]:
    yield from session_scope()


def _load_token(request: Request, db: Session) -> DeviceToken:
    """Verify the bearer header and return the active token row (401 otherwise).

    Shared by ``require_token`` (device routes) and ``require_agent_token`` (the Stage 23
    push API); the kind check is layered on top by each dependency.
    """
    header = request.headers.get("authorization", "")
    scheme, _, value = header.partition(" ")
    if scheme.lower() != "bearer" or not value.strip():
        raise ApiError(401, "unauthorized", "missing or malformed bearer token")
    token = verify_token(db, value.strip())
    if token is None:
        raise ApiError(401, "unauthorized", "invalid or revoked token")
    return token


def require_token(request: Request, db: Session = Depends(get_db)) -> DeviceToken:
    """Device-route auth: a valid bearer whose kind is ``device`` (Stage 23).

    An agent-kind token is a valid credential but is rejected here with ``403 forbidden``
    — the device routes are device-only.
    """
    token = _load_token(request, db)
    if token.kind != "device":
        raise ApiError(403, "forbidden", "this token cannot access device routes")
    return token


def require_agent_token(request: Request, db: Session = Depends(get_db)) -> DeviceToken:
    """Push-API auth: a valid bearer whose kind is ``agent`` (Stage 23).

    A device-kind token is rejected with ``403 forbidden``; missing/invalid/revoked stays
    ``401`` via ``_load_token``.
    """
    token = _load_token(request, db)
    if token.kind != "agent":
        raise ApiError(403, "forbidden", "this route requires an agent token")
    return token
