"""Device bearer tokens: minting, hashing, and verification (ADR-0008).

A token is opaque random text shown once at mint time. Only its salted SHA-256
(peppered with ``INKWELL_TOKEN_PEPPER``) is stored, so the database never holds a
usable credential. Revocation stamps ``revoked_at``.
"""

from __future__ import annotations

import hashlib
import secrets
from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.config import get_settings
from app.db.models import DeviceToken


def hash_token(plaintext: str) -> str:
    pepper = get_settings().token_pepper
    return hashlib.sha256(f"{pepper}:{plaintext}".encode()).hexdigest()


def create_token(session: Session, name: str) -> tuple[DeviceToken, str]:
    """Mint a token. Returns the row and the plaintext (shown to the operator once)."""
    plaintext = secrets.token_urlsafe(32)
    row = DeviceToken(name=name, token_hash=hash_token(plaintext))
    session.add(row)
    session.commit()
    session.refresh(row)
    return row, plaintext


def revoke_token(session: Session, token_id: str) -> bool:
    row = session.get(DeviceToken, token_id)
    if row is None or row.revoked_at is not None:
        return False
    row.revoked_at = datetime.now(UTC)
    session.commit()
    return True


def verify_token(session: Session, plaintext: str) -> DeviceToken | None:
    """Return the active token row for ``plaintext``, or None if unknown/revoked."""
    row = session.execute(
        select(DeviceToken).where(DeviceToken.token_hash == hash_token(plaintext))
    ).scalar_one_or_none()
    if row is None or row.revoked_at is not None:
        return None
    row.last_seen_at = datetime.now(UTC)
    session.commit()
    return row
