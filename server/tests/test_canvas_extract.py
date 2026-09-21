"""Stage 26: canvas.extract ("Remember") end-to-end through the API + worker.

Extract reuses the annotate pipeline; its agent-output ``brain_writes`` are persisted by
stage 25's store (gated by BRAIN_ENABLED). Mirrors the FakeAnthropic idioms of the other
job tests.
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

_EXTRACT_RESPONSE = {
    "summary": "Recorded the durable facts on this canvas.",
    "annotations": [],
    "cards": [
        {
            "kind": "fact",
            "title": "Saved to brain",
            "body": "Recorded the offsite date.",
            "anchors": [],
            "actions": [],
        }
    ],
    "brain_writes": [
        {"kind": "fact", "text": "Q3 offsite is in Austin on 14 October", "tags": ["offsite"]}
    ],
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


def test_extract_type_is_accepted_and_needs_an_image(client, auth):
    # canvas.extract is now an implemented, image-bearing type (not 422 not_implemented).
    resp = client.post("/v1/jobs", json={"type": "canvas.extract"}, headers=auth)
    assert resp.status_code == 422
    assert resp.json()["error"]["code"] == "validation"  # missing image, not not_implemented


def test_extract_end_to_end_persists_writes(client, auth, agent_on, brain_on, db):
    agent_client.set_client(FakeAnthropic(json_payload(_EXTRACT_RESPONSE)))
    resp = client.post(
        "/v1/jobs", json={"type": "canvas.extract", "image": PNG_1X1_B64}, headers=auth
    )
    assert resp.status_code == 202, resp.text
    job_id = resp.json()["id"]
    _run_worker()

    got = client.get(f"/v1/jobs/{job_id}", headers=auth).json()
    assert got["status"] == "done"
    assert got["result"]["contract_version"] == "agent-output/v1"
    # The write landed in the brain with provenance.
    entry = db.execute(select(BrainEntry)).scalar_one()
    assert entry.text == "Q3 offsite is in Austin on 14 October"
    assert entry.tags == ["offsite"]
    assert str(entry.job_id) == job_id
    assert set(got["result"]["brain_entry_ids"]) == {str(entry.id)}
    # Exactly one "Saved to brain" fact card.
    cards = db.execute(select(Card).where(Card.job_id == job_id)).scalars().all()
    assert len(cards) == 1 and cards[0].kind == "fact" and cards[0].title == "Saved to brain"


def test_extract_default_instruction_is_remember(client, auth, agent_on, brain_on):
    fake = FakeAnthropic(json_payload(_EXTRACT_RESPONSE))
    agent_client.set_client(fake)
    resp = client.post(
        "/v1/jobs", json={"type": "canvas.extract", "image": PNG_1X1_B64}, headers=auth
    )
    assert resp.status_code == 202
    _run_worker()
    # No instruction sent -> the default "Remember what is on this canvas." user turn.
    user_text = fake.messages.calls[0]["messages"][0]["content"][1]["text"]
    assert user_text == "Remember what is on this canvas."
    # Extract runs at medium effort (SPEC §7).
    assert fake.messages.calls[0]["output_config"]["effort"] == "medium"
