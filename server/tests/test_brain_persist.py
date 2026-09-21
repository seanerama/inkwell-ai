"""Stage 25: brain_writes persist from a done job with provenance + dedupe (ADR-0013).

Mirrors ``test_canvas_ask.py`` idioms (FakeAnthropic, json_payload, process_one, the
``agent_on`` fixture). ``brain_on`` additionally flips the ``BRAIN_ENABLED`` kill-switch.
"""

from __future__ import annotations

import pytest
from sqlalchemy import select

from app.agent import client as agent_client
from app.config import get_settings
from app.db.base import get_sessionmaker
from app.db.models import BrainEntry, Card, Job
from app.jobs.queue import process_one
from tests.fakes import PNG_1X1_B64, FakeAnthropic, json_payload

_TWO_WRITES = [
    {
        "kind": "fact",
        "text": "The Q3 budget review is on 2026-10-14.",
        "tags": ["Finance", "finance", "  Q3  "],
        "source_region": [0.1, 0.2, 0.3, 0.4],
    },
    {"kind": "task", "text": "Email Dana the signed contract.", "tags": []},
]


def _response(brain_writes: list[dict]) -> dict:
    return {
        "summary": "Read the note and recorded what matters.",
        "annotations": [],
        "cards": [
            {"kind": "answer", "title": "Noted", "body": "Saved.", "anchors": [], "actions": []}
        ],
        "brain_writes": brain_writes,
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


def _submit(client, auth, **extra) -> str:
    body = {"type": "canvas.ask", "image": PNG_1X1_B64, **extra}
    resp = client.post("/v1/jobs", json=body, headers=auth)
    assert resp.status_code == 202, resp.text
    return resp.json()["id"]


def _run_worker() -> None:
    sm = get_sessionmaker()
    with sm() as session:
        assert process_one(session, "test-worker") is True


def test_two_writes_persist_with_provenance(client, auth, agent_on, brain_on, db):
    agent_client.set_client(FakeAnthropic(json_payload(_response(_TWO_WRITES))))
    job_id = _submit(client, auth)
    _run_worker()

    got = client.get(f"/v1/jobs/{job_id}", headers=auth).json()
    assert got["status"] == "done"
    entries = db.execute(select(BrainEntry).order_by(BrainEntry.created_at)).scalars().all()
    assert len(entries) == 2
    for e in entries:
        assert e.space_slug == "work"  # canvas.ask defaults to the work space
        assert e.job_id is not None and str(e.job_id) == job_id
        assert e.hash

    # brain_entry_ids sibling records both created rows (order is insert order).
    assert set(got["result"]["brain_entry_ids"]) == {str(e.id) for e in entries}
    # The frozen brain_writes payload is unchanged (both writes still present).
    assert len(got["result"]["brain_writes"]) == 2

    fact = next(e for e in entries if e.kind == "fact")
    assert fact.source_region == [0.1, 0.2, 0.3, 0.4]
    # Tag normalisation: lowercase + strip + dedupe.
    assert fact.tags == ["finance", "q3"]


def test_same_writes_again_dedupe_zero_new(client, auth, agent_on, brain_on, db):
    agent_client.set_client(FakeAnthropic(json_payload(_response(_TWO_WRITES))))
    _submit(client, auth)
    _run_worker()
    assert db.execute(select(BrainEntry)).scalars().all().__len__() == 2

    # A second identical job produces zero new entries and an empty sibling list.
    agent_client.set_client(FakeAnthropic(json_payload(_response(_TWO_WRITES))))
    job2 = _submit(client, auth)
    _run_worker()
    got2 = client.get(f"/v1/jobs/{job2}", headers=auth).json()
    assert got2["status"] == "done"
    assert got2["result"]["brain_entry_ids"] == []
    assert len(db.execute(select(BrainEntry)).scalars().all()) == 2


def test_tag_cap_at_ten(client, auth, agent_on, brain_on, db):
    writes = [
        {
            "kind": "fact",
            "text": "A fact with too many tags.",
            "tags": [f"tag{i}" for i in range(15)],
        }
    ]
    agent_client.set_client(FakeAnthropic(json_payload(_response(writes))))
    _submit(client, auth)
    _run_worker()
    entry = db.execute(select(BrainEntry)).scalar_one()
    assert entry.tags == [f"tag{i}" for i in range(10)]


def test_kill_switch_off_persists_nothing_and_no_sibling(client, auth, agent_on, db):
    assert get_settings().brain_enabled is False
    agent_client.set_client(FakeAnthropic(json_payload(_response(_TWO_WRITES))))
    job_id = _submit(client, auth)
    _run_worker()
    got = client.get(f"/v1/jobs/{job_id}", headers=auth).json()
    assert got["status"] == "done"
    assert "brain_entry_ids" not in got["result"]
    assert db.execute(select(BrainEntry)).scalars().all() == []


def test_persistence_failure_fails_job_with_error_card(
    client, auth, agent_on, brain_on, db, monkeypatch
):
    def _boom(session, job, writes):
        raise RuntimeError("brain store exploded")

    monkeypatch.setattr("app.brain.store.persist_writes", _boom)
    agent_client.set_client(FakeAnthropic(json_payload(_response(_TWO_WRITES))))
    job_id = _submit(client, auth)
    _run_worker()

    got = client.get(f"/v1/jobs/{job_id}", headers=auth).json()
    assert got["status"] == "failed"
    cards = db.execute(select(Card).where(Card.job_id == job_id)).scalars().all()
    assert len(cards) == 1 and cards[0].kind == "error"
    # Nothing was persisted (the whole done transaction rolled back).
    assert db.execute(select(BrainEntry)).scalars().all() == []
    assert db.get(Job, job_id).result is None
