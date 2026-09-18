"""Agent push API (Stage 23, additive to contract device-api v1).

Any script holding an **agent-kind** token can push a document or a blank canvas over
HTTPS from the tailnet, reusing the Stage 21 push service (ADR-0012). Both routes require
an agent token (``require_agent_token`` — a device token is rejected ``403 forbidden``),
honour the existing ``PUSH_ENABLED`` kill-switch (``403 disabled``), resolve the target
space by slug (``404 not_found``), and are rate-limited per agent token (``429``). The
document upload reuses the exact ``POST /blobs`` limits and magic-byte sniffing.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, File, Form, Request, UploadFile
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.deps import get_db, require_agent_token
from app.api.errors import ApiError
from app.api.routes.blobs import _require_push_enabled
from app.api.schemas import PushCanvasIn, PushOut
from app.db.models import DeviceToken, Space
from app.push.service import (
    A4_H,
    A4_W,
    MAX_BLOB_BYTES,
    PushError,
    push_canvas,
    push_document,
    sniff_mime,
)

router = APIRouter()


def _resolve_space(db: Session, slug: str) -> Space:
    space = db.execute(select(Space).where(Space.slug == slug)).scalar_one_or_none()
    if space is None:
        raise ApiError(404, "not_found", f"no space with slug {slug!r}")
    return space


def _rate_limit(request: Request, token: DeviceToken) -> None:
    """10/min per agent token id (mirrors ``POST /jobs``); the 11th → ``429``."""
    limiter = request.app.state.push_rate_limiter
    allowed, retry_after = limiter.check(str(token.id))
    if not allowed:
        raise ApiError(
            429,
            "rate_limited",
            "push rate limit exceeded",
            headers={"Retry-After": str(retry_after)},
        )


@router.post("/push/document", response_model=PushOut, status_code=201)
async def push_document_route(
    request: Request,
    file: Annotated[UploadFile, File()],
    space: Annotated[str, Form()],
    title: Annotated[str | None, Form()] = None,
    note: Annotated[str | None, Form()] = None,
    db: Session = Depends(get_db),
    token: DeviceToken = Depends(require_agent_token),
) -> PushOut:
    resolved = _resolve_space(db, space)
    _require_push_enabled()
    _rate_limit(request, token)

    data = await file.read()
    # Reuse the POST /blobs limits/sniffing so the two upload surfaces stay identical.
    if len(data) > MAX_BLOB_BYTES:
        raise ApiError(413, "too_large", "file exceeds the 20 MB limit")
    if sniff_mime(data) is None:
        raise ApiError(
            415,
            "unsupported_media_type",
            "unsupported file type (allowed: application/pdf, image/png, image/jpeg)",
        )
    try:
        job, canvases = push_document(
            db, resolved, data, mime=None, title=title or "Document", card_body=note
        )
    except PushError as exc:
        # Size/mime are pre-checked above; what remains is a page-cap / unreadable PDF.
        raise ApiError(422, "validation", str(exc)) from exc
    return PushOut(job_id=str(job.id), canvas_ids=[str(c.id) for c in canvases])


@router.post("/push/canvas", response_model=PushOut, status_code=201)
def push_canvas_route(
    body: PushCanvasIn,
    request: Request,
    db: Session = Depends(get_db),
    token: DeviceToken = Depends(require_agent_token),
) -> PushOut:
    resolved = _resolve_space(db, body.space)
    _require_push_enabled()
    _rate_limit(request, token)

    width, height = (A4_H, A4_W) if body.landscape else (A4_W, A4_H)
    try:
        job, canvas = push_canvas(db, resolved, body.title, width, height, card_body=body.note)
    except PushError as exc:
        raise ApiError(422, "validation", str(exc)) from exc
    return PushOut(job_id=str(job.id), canvas_ids=[str(canvas.id)])
