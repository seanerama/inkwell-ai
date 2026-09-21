"""Stage 25: the ``save_to_brain`` card action (ADR-0013 §5, contract device-api).

With ``BRAIN_ENABLED`` on it creates a brain entry from the card and marks the card
``done``, returning a ``brain_entry_id`` sibling; off it stays ``422 not_implemented``
(that default-off case is covered in test_cards.py).
"""

from __future__ import annotations

import pytest
from sqlalchemy import select

from app.agent import client as agent_client
from app.config import get_settings
from app.db.base import get_sessionmaker
from app.db.models import BrainEntry, Card
from app.jobs.queue import process_one
from tests.fakes import PNG_1X1_B64, FakeAnthropic, json_payload

ASK_RESPONSE = {
    "summary": "Answered and offered to save.",
    "annotations": [],
    "cards": [
        {
            "kind": "answer",
            "title": "Buy milk",
            "body": "Two litres, semi-skimmed.",
            "anchors": [],
            "actions": [
                {
                    "id": "c3",
                    "label": "Save as task",
                    "kind": "save_to_brain",
                    "payload": {"kind": "task", "tags": ["Shopping"]},
                },
            ],
        }
    ],
    "brain_writes": [],
}


@pytest.fixture(autouse=True)
def _reset_client():
    yield
    agent_client.set_client(None)


@pytest.fixture
def agent_on(monkeypatch):
    monkeypatch.setattr(get_settings(), "agent_enabled", True)


@pytest.fixture
def brain_on(monkeypatch):
    monkeypatch.setattr(get_settings(), "brain_enabled", True)


def _run_worker() -> None:
    sm = get_sessionmaker()
    with sm() as session:
        assert process_one(session, "test-worker") is True


def _seed_card(client, auth) -> str:
    agent_client.set_client(FakeAnthropic(json_payload(ASK_RESPONSE)))
    resp = client.post("/v1/jobs", json={"type": "canvas.ask", "image": PNG_1X1_B64}, headers=auth)
    assert resp.status_code == 202
    _run_worker()
    got = client.get(f"/v1/jobs/{resp.json()['id']}", headers=auth).json()
    assert got["status"] == "done"
    return got["cards"][0]["id"]


def test_save_to_brain_creates_entry_marks_done_and_returns_sibling(
    client, auth, agent_on, brain_on, db
):
    card_id = _seed_card(client, auth)
    resp = client.post(f"/v1/cards/{card_id}/actions/c3", headers=auth)
    assert resp.status_code == 200, resp.text
    payload = resp.json()
    assert payload["state"] == "done"
    assert "brain_entry_id" in payload

    entry = db.execute(select(BrainEntry)).scalar_one()
    assert str(entry.id) == payload["brain_entry_id"]
    assert entry.kind == "task"  # payload kind is one of the four brain kinds
    assert entry.text == "Buy milk\n\nTwo litres, semi-skimmed."
    assert entry.tags == ["shopping"]  # normalised
    assert entry.space_slug == "work"

    # The card row is really done.
    card = db.get(Card, card_id)
    assert card.state == "done"


def test_save_to_brain_off_is_not_implemented(client, auth, agent_on, db):
    assert get_settings().brain_enabled is False
    card_id = _seed_card(client, auth)
    resp = client.post(f"/v1/cards/{card_id}/actions/c3", headers=auth)
    assert resp.status_code == 422
    assert resp.json()["error"]["code"] == "not_implemented"
    assert db.execute(select(BrainEntry)).scalars().all() == []
