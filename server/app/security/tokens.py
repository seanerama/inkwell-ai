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


def _hash_with(pepper: str, plaintext: str) -> str:
    """SHA-256 of ``<pepper>:<plaintext>``. Never log ``pepper`` or ``plaintext``."""
    return hashlib.sha256(f"{pepper}:{plaintext}".encode()).hexdigest()


def hash_token(plaintext: str) -> str:
    return _hash_with(get_settings().token_pepper, plaintext)


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
    """Return the active token row for ``plaintext``, or None if unknown/revoked.

    ADR-0008 dual-pepper grace window (Stage 18): look the token up by the current-pepper
    hash first (a DB index equality, constant-time as today). On a miss, and only when
    ``token_pepper_previous`` is set, look it up by the previous-pepper hash; if that row
    is live, re-hash it to the current pepper in this same request and bump
    ``hash_version``. A revoked row is never resurrected under either pepper.
    """
    settings = get_settings()

    row = session.execute(
        select(DeviceToken).where(
            DeviceToken.token_hash == _hash_with(settings.token_pepper, plaintext)
        )
    ).scalar_one_or_none()
    if row is not None:
        if row.revoked_at is not None:
            return None
        row.last_seen_at = datetime.now(UTC)
        session.commit()
        return row

    previous = settings.token_pepper_previous
    if previous:
        row = session.execute(
            select(DeviceToken).where(DeviceToken.token_hash == _hash_with(previous, plaintext))
        ).scalar_one_or_none()
        if row is not None:
            if row.revoked_at is not None:
                return None
            # Migrate the row to the current pepper in this request.
            row.token_hash = _hash_with(settings.token_pepper, plaintext)
            row.hash_version = (row.hash_version or 1) + 1
            row.last_seen_at = datetime.now(UTC)
            session.commit()
            return row

    return None
