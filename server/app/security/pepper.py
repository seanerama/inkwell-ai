"""Pepper-generation marker in ``app_meta`` (Stage 20, ADR-0008).

The pepper *generation* is a monotonically increasing integer, bumped once per rotation
by ``inkwell token rotate-pepper --begin``. ``verify_token`` stamps a re-hashed row with
the CURRENT generation, so ``migrate-check`` can tell — without guessing from
``MAX(hash_version)`` — which live tokens still lag. Guessing from MAX false-greens at the
very start of a rotation (before any token migrates, MAX is still the old value), which is
exactly the Stage 20 bug this replaces.

A MISSING ``pepper_generation`` row is treated as generation 1, so existing installs and
the migration-check downgrade/upgrade cycle behave without a seed row.
"""

from __future__ import annotations

from sqlalchemy.orm import Session

from app.db.models import AppMeta

_PEPPER_GENERATION_KEY = "pepper_generation"


def get_pepper_generation(session: Session) -> int:
    """Current pepper generation; a missing ``app_meta`` row means generation 1."""
    row = session.get(AppMeta, _PEPPER_GENERATION_KEY)
    if row is None:
        return 1
    return int(row.value)


def set_pepper_generation(session: Session, n: int) -> None:
    """Persist the pepper generation (creates the ``app_meta`` row if absent)."""
    row = session.get(AppMeta, _PEPPER_GENERATION_KEY)
    if row is None:
        session.add(AppMeta(key=_PEPPER_GENERATION_KEY, value=str(n)))
    else:
        row.value = str(n)
    session.commit()
