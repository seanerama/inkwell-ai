"""Card routes (Stage 10 — additive to contract device-api v1).

Cards are real objects the device can change: it can set their ``state`` and invoke
their ``actions``. Both operations bump the parent job's ``updated_at`` so the change
is delivered through ``/sync`` (which orders by ``updated_at``); only the card row
changes otherwise, so the bump is explicit.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.api.deps import get_db, require_token
from app.api.errors import ApiError
from app.api.schemas import CardOut
from app.brain.store import create_entry
from app.config import get_settings
from app.db.models import BRAIN_KINDS, CARD_STATES, Card, DeviceToken, Space

router = APIRouter()

# Allowed device-driven state transitions: open <-> done and open <-> dismissed.
# done <-> dismissed is NOT allowed directly (SPEC §4.7).
_ALLOWED_TRANSITIONS = {
    ("open", "done"),
    ("open", "dismissed"),
    ("done", "open"),
    ("dismissed", "open"),
}


class CardPatch(BaseModel):
    state: str


def _bump_job(db: Session, card: Card) -> None:
    """Bump the parent job's ``updated_at`` so ``/sync`` delivers the card change."""
    if card.job is not None:
        card.job.updated_at = datetime.now(UTC)


def _get_card(db: Session, card_id: uuid.UUID) -> Card:
    card = db.get(Card, card_id)
    if card is None:
        raise ApiError(404, "not_found", "unknown card id")
    return card


@router.patch("/cards/{card_id}", response_model=CardOut)
def patch_card(
    card_id: uuid.UUID,
    body: CardPatch,
    db: Session = Depends(get_db),
    _: DeviceToken = Depends(require_token),
) -> CardOut:
    if body.state not in CARD_STATES:
        raise ApiError(422, "validation", f"unknown card state {body.state!r}")
    card = _get_card(db, card_id)
    if body.state != card.state and (card.state, body.state) not in _ALLOWED_TRANSITIONS:
        raise ApiError(409, "conflict", f"invalid transition {card.state} -> {body.state}")
    if body.state != card.state:
        card.state = body.state
        _bump_job(db, card)
        db.commit()
        db.refresh(card)
    return CardOut.model_validate(card)


def _save_to_brain(db: Session, card: Card, action: dict) -> dict:
    """Stage 25 (ADR-0013 §5): create a brain entry from the card, mark it ``done``.

    Returns the card as today (``CardOut`` fields) plus a ``brain_entry_id`` sibling. The
    entry's ``kind`` is the action payload's ``kind`` when it is one of the four brain
    kinds, else ``fact``; its text is the card's title and body; tags come from the
    payload; ``source_canvas_id`` from the parent job. Gated by ``BRAIN_ENABLED``.
    """
    payload = action.get("payload") or {}
    kind = payload.get("kind")
    if kind not in BRAIN_KINDS:
        kind = "fact"
    text = f"{card.title}\n\n{card.body}" if card.body else card.title
    job = card.job
    space = db.get(Space, job.space_id) if job is not None else None
    if space is None:  # pragma: no cover - a card always has a job with a real space
        raise ApiError(404, "not_found", "card has no space")

    entry, _created = create_entry(
        db,
        space_slug=space.slug,
        kind=kind,
        text=text,
        tags=payload.get("tags") or [],
        source_canvas_id=job.canvas_id if job is not None else None,
    )
    if card.state != "done":
        card.state = "done"
    _bump_job(db, card)
    db.commit()
    db.refresh(card)
    db.refresh(entry)
    out = CardOut.model_validate(card).model_dump(mode="json")
    out["brain_entry_id"] = str(entry.id)
    return out


@router.post("/cards/{card_id}/actions/{action_id}")
def run_card_action(
    card_id: uuid.UUID,
    action_id: str,
    db: Session = Depends(get_db),
    _: DeviceToken = Depends(require_token),
) -> dict:
    card = _get_card(db, card_id)
    action = next((a for a in (card.actions or []) if a.get("id") == action_id), None)
    if action is None:
        raise ApiError(404, "not_found", "unknown action id")

    kind = action.get("kind")
    if kind == "confirm":
        new_state = "done"
    elif kind == "reject":
        new_state = "dismissed"
    elif kind == "save_to_brain":
        if not get_settings().brain_enabled:
            raise ApiError(
                422, "not_implemented", "action kind save_to_brain is not implemented yet"
            )
        return _save_to_brain(db, card, action)
    elif kind in ("run_tool", "open_canvas"):
        raise ApiError(422, "not_implemented", f"action kind {kind} is not implemented yet")
    else:
        raise ApiError(422, "validation", f"unknown action kind {kind!r}")

    if new_state != card.state:
        card.state = new_state
        _bump_job(db, card)
        db.commit()
        db.refresh(card)
    return CardOut.model_validate(card).model_dump(mode="json")
