"""Blob routes (frozen contract device-api): ``POST /blobs`` + ``GET /blobs/{key}``.

Both are gated by the ``PUSH_ENABLED`` kill-switch (default OFF, ADR-0012). ``POST``
accepts a multipart upload, sniffs the magic bytes (never trusting the declared type),
caps the size, and returns a signed, expiring URL. ``GET`` requires the device bearer
token **and** a valid HMAC signature (ADR-0004) and streams the stored bytes.
"""

from __future__ import annotations

import time
import uuid
from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter, Depends, File, Response, UploadFile

from app.api.deps import require_token
from app.api.errors import ApiError
from app.api.schemas import BlobOut
from app.blobs import get_blob_store
from app.config import get_settings
from app.db.models import DeviceToken
from app.push.service import (
    MAX_BLOB_BYTES,
    PUSH_URL_TTL,
    ext_for_mime,
    mime_for_key,
    sniff_mime,
)

router = APIRouter()


def _require_push_enabled() -> None:
    if not get_settings().push_enabled:
        raise ApiError(403, "disabled", "push is disabled (PUSH_ENABLED is off)")


@router.post("/blobs", response_model=BlobOut, status_code=201)
async def upload_blob(
    file: Annotated[UploadFile, File()],
    _: DeviceToken = Depends(require_token),
) -> BlobOut:
    _require_push_enabled()
    data = await file.read()
    if len(data) > MAX_BLOB_BYTES:
        raise ApiError(413, "too_large", "file exceeds the 20 MB limit")
    mime = sniff_mime(data)
    if mime is None:
        raise ApiError(
            415,
            "unsupported_media_type",
            "unsupported file type (allowed: application/pdf, image/png, image/jpeg)",
        )
    key = f"push/{uuid.uuid4()}.{ext_for_mime(mime)}"
    store = get_blob_store()
    store.put(key, data, mime)

    exp = int(time.time()) + PUSH_URL_TTL
    sig = store.sign(key, exp)
    url = f"/v1/blobs/{key}?sig={sig}&exp={exp}"
    return BlobOut(key=key, url=url, expires_at=datetime.fromtimestamp(exp, UTC))


@router.get("/blobs/{key:path}")
def download_blob(
    key: str,
    sig: str | None = None,
    exp: int | None = None,
    _: DeviceToken = Depends(require_token),
) -> Response:
    _require_push_enabled()
    store = get_blob_store()
    # Signature required IN ADDITION to the bearer token (ADR-0004). Verify before any
    # key lookup so a bad signature cannot be used to probe which keys exist.
    if sig is None or exp is None or not store.verify(key, exp, sig):
        raise ApiError(403, "forbidden", "missing or invalid blob signature")
    try:
        data = store.open(key)
    except KeyError as exc:
        raise ApiError(404, "not_found", "unknown blob key") from exc

    mime = mime_for_key(key) or "application/octet-stream"
    remaining = max(0, exp - int(time.time()))
    return Response(
        content=data,
        media_type=mime,
        headers={"Cache-Control": f"private, max-age={remaining}"},
    )
