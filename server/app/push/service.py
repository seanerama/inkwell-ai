"""Server-side push: store a blob once, materialise agent-origin canvases + a to_user job.

ADR-0012: the server stores the original bytes (PDF/PNG/JPEG) and creates one
``origin=agent`` canvas per page with a ``raster`` layer at ``z=-1``; the device renders
the page. Nothing runs through the worker — the ``to_user`` job is created ``done`` with
the contract's ``{ canvas, layers, rasters, cards }`` result (plus the additive
``canvases`` sibling for multi-page documents, and an additive signed ``url`` on each
raster). The model is never involved.
"""

from __future__ import annotations

import io
import uuid

from sqlalchemy.orm import Session

from app.api.schemas import CanvasOut, CardOut, LayerOut, RasterOut
from app.blobs import get_blob_store
from app.db.models import Canvas, Card, Job, Layer, Raster, Space

# Upload limits (ADR-0012): 20 MB per blob, PDFs capped at 20 pages.
MAX_BLOB_BYTES = 20 * 1024 * 1024
MAX_PDF_PAGES = 20
# Signed-URL TTL for pushed rasters and blob GETs: 24 h, so a device that missed the
# initial sync window can still re-fetch the bytes without signing anything.
PUSH_URL_TTL = 24 * 60 * 60

# A4 portrait in canvas units (SPEC §4.2 defaults). Landscape pages get the swap, which
# keeps the same area (ADR-0012 fit rule).
A4_W = 2480
A4_H = 3508

MIME_ALLOW = ("application/pdf", "image/png", "image/jpeg")
_EXT_FOR_MIME = {"application/pdf": "pdf", "image/png": "png", "image/jpeg": "jpg"}
_MIME_FOR_EXT = {
    "pdf": "application/pdf",
    "png": "image/png",
    "jpg": "image/jpeg",
    "jpeg": "image/jpeg",
}


class PushError(ValueError):
    """A push was rejected for a client-visible reason (bad type, too big, too many pages)."""


def sniff_mime(data: bytes) -> str | None:
    """Return the mime from the leading magic bytes, or ``None`` if not allow-listed.

    The declared upload type is never trusted; only the bytes decide (SPEC §11).
    """
    if data[:5] == b"%PDF-":
        return "application/pdf"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if data[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    return None


def ext_for_mime(mime: str) -> str:
    return _EXT_FOR_MIME[mime]


def mime_for_key(key: str) -> str | None:
    """Infer the Content-Type of a stored blob from its ``<uuid>.<ext>`` key.

    ``LocalBlobStore.put`` does not persist the mime, and keys carry the extension, so the
    extension is the source of truth on read.
    """
    ext = key.rsplit(".", 1)[-1].lower() if "." in key else ""
    return _MIME_FOR_EXT.get(ext)


def signed_blob_url(key: str, ttl: int = PUSH_URL_TTL) -> str:
    """A fresh signed ``GET /v1/blobs/{key}`` link (default 24 h)."""
    return get_blob_store().signed_url(key, ttl)


# --- intrinsic dimensions (no Pillow at runtime; parse the headers directly) ---------


def _png_size(data: bytes) -> tuple[int, int] | None:
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    width = int.from_bytes(data[16:20], "big")
    height = int.from_bytes(data[20:24], "big")
    if width <= 0 or height <= 0:
        return None
    return width, height


def _jpeg_size(data: bytes) -> tuple[int, int] | None:
    n = len(data)
    i = 2  # skip the SOI marker
    while i + 9 < n:
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        # SOF markers carry the frame dimensions; skip the non-SOF FF C4/C8/CC segments.
        if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
            height = int.from_bytes(data[i + 5 : i + 7], "big")
            width = int.from_bytes(data[i + 7 : i + 9], "big")
            if width <= 0 or height <= 0:
                return None
            return width, height
        if marker in (0xD8, 0xD9) or 0xD0 <= marker <= 0xD7:
            i += 2
            continue
        seg_len = int.from_bytes(data[i + 2 : i + 4], "big")
        if seg_len < 2:
            return None
        i += 2 + seg_len
    return None


def _image_size(data: bytes, mime: str) -> tuple[int, int] | None:
    if mime == "image/png":
        return _png_size(data)
    if mime == "image/jpeg":
        return _jpeg_size(data)
    return None


def _pdf_page_sizes(data: bytes) -> list[tuple[float, float]]:
    """Per-page (width, height) in PDF points; raises PushError past the page cap."""
    from pypdf import PdfReader

    try:
        reader = PdfReader(io.BytesIO(data))
        pages = reader.pages
        count = len(pages)
    except PushError:
        raise
    except Exception as exc:  # a corrupt / unreadable PDF is a client error
        raise PushError(f"could not read the PDF: {exc}") from exc
    if count == 0:
        raise PushError("the PDF has no pages")
    if count > MAX_PDF_PAGES:
        raise PushError(f"the PDF has {count} pages; the limit is {MAX_PDF_PAGES}")
    sizes: list[tuple[float, float]] = []
    for page in pages:
        box = page.mediabox
        sizes.append((float(box.width), float(box.height)))
    return sizes


def _fit(dims: tuple[float, float] | None) -> tuple[int, int, float, float]:
    """Canvas size + raster placement for one page (ADR-0012: fit to canvas width, top-left).

    Portrait pages get an A4-portrait canvas, landscape pages the same-area swap. The
    raster spans the full canvas width; its height keeps the source aspect ratio.
    """
    if dims is None or dims[0] <= 0 or dims[1] <= 0:
        iw, ih = float(A4_W), float(A4_H)
    else:
        iw, ih = dims
    if iw > ih:
        canvas_w, canvas_h = A4_H, A4_W
    else:
        canvas_w, canvas_h = A4_W, A4_H
    w_cu = float(canvas_w)
    h_cu = round(canvas_w * (ih / iw), 2)
    return canvas_w, canvas_h, w_cu, h_cu


# --- result serialisation (frozen device-api shapes + additive fields) ---------------


def _canvas_dict(canvas: Canvas) -> dict:
    return CanvasOut.model_validate(canvas).model_dump(mode="json")


def _layer_dict(layer: Layer) -> dict:
    return LayerOut.model_validate(layer).model_dump(mode="json")


def _raster_dict(raster: Raster) -> dict:
    out = RasterOut.model_validate(raster)
    out.url = signed_blob_url(raster.blob_uri)
    return out.model_dump(mode="json")


def _card_dict(card: Card) -> dict:
    return CardOut.model_validate(card).model_dump(mode="json")


# --- the two push entry points -------------------------------------------------------


def push_document(
    session: Session,
    space: Space,
    data: bytes,
    mime: str | None = None,
    title: str = "Document",
    card_body: str | None = None,
) -> tuple[Job, list[Canvas]]:
    """Store ``data`` once and create one agent-origin canvas per page + a done to_user job.

    The mime is sniffed from the bytes (the declared ``mime`` is only a hint). Returns the
    created job and its canvases.
    """
    if len(data) > MAX_BLOB_BYTES:
        raise PushError("file exceeds the 20 MB limit")
    sniffed = sniff_mime(data)
    if sniffed is None:
        raise PushError("unsupported file type (allowed: application/pdf, image/png, image/jpeg)")
    mime = sniffed

    key = f"push/{uuid.uuid4()}.{ext_for_mime(mime)}"
    get_blob_store().put(key, data, mime)

    if mime == "application/pdf":
        page_dims = _pdf_page_sizes(data)
        is_pdf = True
    else:
        page_dims = [_image_size(data, mime)]
        is_pdf = False
    multi = len(page_dims) > 1

    job = Job(
        space_id=space.id,
        direction="to_user",
        type="agent.push_document",
        status="done",
        request={},
    )
    session.add(job)
    session.flush()

    canvases: list[Canvas] = []
    layers: list[Layer] = []
    rasters: list[Raster] = []
    for idx, dims in enumerate(page_dims, start=1):
        canvas_w, canvas_h, w_cu, h_cu = _fit(dims)
        canvas = Canvas(
            space_id=space.id,
            title=f"{title} — p{idx}" if multi else title,
            width_cu=canvas_w,
            height_cu=canvas_h,
            origin="agent",
        )
        session.add(canvas)
        session.flush()
        layer = Layer(canvas_id=canvas.id, z=-1, owner="agent", type="raster", job_id=job.id)
        session.add(layer)
        session.flush()
        raster = Raster(
            layer_id=layer.id,
            blob_uri=key,
            mime=mime,
            page=idx if is_pdf else None,
            x_cu=0.0,
            y_cu=0.0,
            w_cu=w_cu,
            h_cu=h_cu,
        )
        session.add(raster)
        session.flush()
        canvases.append(canvas)
        layers.append(layer)
        rasters.append(raster)

    job.canvas_id = canvases[0].id

    cards: list[Card] = []
    if card_body is not None:
        card = Card(
            job_id=job.id, kind="answer", title=title, body=card_body, anchors=[], actions=[]
        )
        session.add(card)
        session.flush()
        cards.append(card)

    job.result = {
        "canvas": _canvas_dict(canvases[0]),
        "canvases": [_canvas_dict(c) for c in canvases],
        "layers": [_layer_dict(item) for item in layers],
        "rasters": [_raster_dict(item) for item in rasters],
        "cards": [_card_dict(c) for c in cards],
    }
    session.commit()
    session.refresh(job)
    return job, canvases


def push_canvas(
    session: Session,
    space: Space,
    title: str,
    width: int,
    height: int,
    card_body: str | None = None,
) -> tuple[Job, Canvas]:
    """Create a blank agent-origin canvas + a done to_user job (no layer — stage 12 rule).

    The device creates the ``user/ink`` layer on the first stroke, so the push writes only
    the canvas row and an ``agent.push_canvas`` job.
    """
    job = Job(
        space_id=space.id,
        direction="to_user",
        type="agent.push_canvas",
        status="done",
        request={},
    )
    session.add(job)
    session.flush()

    canvas = Canvas(
        space_id=space.id, title=title, width_cu=width, height_cu=height, origin="agent"
    )
    session.add(canvas)
    session.flush()
    job.canvas_id = canvas.id

    cards: list[Card] = []
    if card_body is not None:
        card = Card(
            job_id=job.id, kind="answer", title=title, body=card_body, anchors=[], actions=[]
        )
        session.add(card)
        session.flush()
        cards.append(card)

    job.result = {
        "canvas": _canvas_dict(canvas),
        "layers": [],
        "rasters": [],
        "cards": [_card_dict(c) for c in cards],
    }
    session.commit()
    session.refresh(job)
    return job, canvas
