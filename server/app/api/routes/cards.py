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
from app.db.models import CARD_STATES, Card, DeviceToken

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


@router.post("/cards/{card_id}/actions/{action_id}", response_model=CardOut)
def run_card_action(
    card_id: uuid.UUID,
    action_id: str,
    db: Session = Depends(get_db),
    _: DeviceToken = Depends(require_token),
) -> CardOut:
    card = _get_card(db, card_id)
    action = next((a for a in (card.actions or []) if a.get("id") == action_id), None)
    if action is None:
        raise ApiError(404, "not_found", "unknown action id")

    kind = action.get("kind")
    if kind == "confirm":
        new_state = "done"
    elif kind == "reject":
        new_state = "dismissed"
    elif kind in ("run_tool", "open_canvas", "save_to_brain"):
        raise ApiError(422, "not_implemented", f"action kind {kind} is not implemented yet")
    else:
        raise ApiError(422, "validation", f"unknown action kind {kind!r}")

    if new_state != card.state:
        card.state = new_state
        _bump_job(db, card)
        db.commit()
        db.refresh(card)
    return CardOut.model_validate(card)
