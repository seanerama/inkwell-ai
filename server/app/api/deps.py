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


def require_token(request: Request, db: Session = Depends(get_db)) -> DeviceToken:
    header = request.headers.get("authorization", "")
    scheme, _, value = header.partition(" ")
    if scheme.lower() != "bearer" or not value.strip():
        raise ApiError(401, "unauthorized", "missing or malformed bearer token")
    token = verify_token(db, value.strip())
    if token is None:
        raise ApiError(401, "unauthorized", "invalid or revoked token")
    return token
