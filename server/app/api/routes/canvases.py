"""Canvas routes (Stage 12 — additive to contract device-api v1).

The server only knows the canvases it creates itself — the agent-origin redraws made by
``canvas.formalize`` (SPEC §4.2, §7). User canvases live on the device and are not
uploaded this phase. ``GET /canvases`` therefore lists the server-known (agent-origin)
canvases, giving Phase 4's canvas push a base to build on.
"""

from __future__ import annotations

import uuid
from datetime import datetime

from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.deps import get_db, require_token
from app.api.errors import ApiError
from app.api.schemas import CanvasDetailOut, CanvasOut, LayerOut, RasterOut
from app.db.models import Canvas, DeviceToken, Layer, Raster
from app.push.service import signed_blob_url

router = APIRouter()


def _parse_since(since: str) -> datetime:
    """Parse an RFC3339 ``since`` filter; a trailing ``Z`` is accepted."""
    try:
        return datetime.fromisoformat(since.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ApiError(422, "validation", "since must be an RFC3339 timestamp") from exc


@router.get("/canvases", response_model=list[CanvasOut])
def list_canvases(
    space_id: uuid.UUID | None = None,
    since: str | None = None,
    db: Session = Depends(get_db),
    _: DeviceToken = Depends(require_token),
) -> list[CanvasOut]:
    stmt = select(Canvas).where(Canvas.origin == "agent")
    if space_id is not None:
        stmt = stmt.where(Canvas.space_id == space_id)
    if since is not None:
        stmt = stmt.where(Canvas.created_at >= _parse_since(since))
    stmt = stmt.order_by(Canvas.created_at.desc())
    canvases = db.execute(stmt).scalars().all()
    return [CanvasOut.model_validate(c) for c in canvases]


@router.get("/canvases/{canvas_id}", response_model=CanvasDetailOut)
def get_canvas(
    canvas_id: uuid.UUID,
    db: Session = Depends(get_db),
    _: DeviceToken = Depends(require_token),
) -> CanvasDetailOut:
    """Canvas detail: the canvas plus its layers and rasters (frozen shape, never strokes).

    Each raster carries an additive, freshly signed ``url`` (24 h, ADR-0012) so a device
    can fetch the bytes without holding the blob signing key.
    """
    canvas = db.get(Canvas, canvas_id)
    if canvas is None:
        raise ApiError(404, "not_found", "unknown canvas id")

    layers = (
        db.execute(select(Layer).where(Layer.canvas_id == canvas_id).order_by(Layer.z))
        .scalars()
        .all()
    )
    layer_ids = [layer.id for layer in layers]
    rasters: list[Raster] = []
    if layer_ids:
        rasters = list(
            db.execute(select(Raster).where(Raster.layer_id.in_(layer_ids))).scalars().all()
        )

    raster_out: list[RasterOut] = []
    for raster in rasters:
        out = RasterOut.model_validate(raster)
        out.url = signed_blob_url(raster.blob_uri)
        raster_out.append(out)

    detail = CanvasDetailOut.model_validate(canvas)
    detail.layers = [LayerOut.model_validate(layer) for layer in layers]
    detail.rasters = raster_out
    return detail
