"""Opaque sync cursor encoding ``(updated_at, id)`` (contract ``device-api``).

Results are ordered by ``(updated_at, id)`` ascending; a cursor marks the last job a
client has seen and is stable across server restarts because it is derived only from
durable row values.
"""

from __future__ import annotations

import base64
import binascii
import uuid
from datetime import datetime


def encode_cursor(updated_at: datetime, job_id: uuid.UUID) -> str:
    raw = f"{updated_at.isoformat()}|{job_id}".encode()
    return base64.urlsafe_b64encode(raw).decode()


def decode_cursor(cursor: str) -> tuple[datetime, uuid.UUID] | None:
    if not cursor:
        return None
    try:
        raw = base64.urlsafe_b64decode(cursor.encode()).decode()
        ts_str, id_str = raw.split("|", 1)
        return datetime.fromisoformat(ts_str), uuid.UUID(id_str)
    except (ValueError, binascii.Error):
        return None
