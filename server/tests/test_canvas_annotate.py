"""Integration: canvas.annotate through the API + worker with a fake client (Stage 5)."""

from __future__ import annotations

import pytest
from sqlalchemy import select

from app.agent import client as agent_client
from app.config import get_settings
from app.db.base import get_sessionmaker
from app.db.models import Card, Job
from app.jobs.queue import process_one
from tests.fakes import PNG_1X1_B64, FakeAnthropic, fixture, json_payload


@pytest.fixture(autouse=True)
def _reset_client():
    yield
    agent_client.set_client(None)


@pytest.fixture
def agent_on(monkeypatch):
    monkeypatch.setattr(get_settings(), "agent_enabled", True)


def _submit(client, auth, **extra) -> str:
    body = {"type": "canvas.annotate", "image": PNG_1X1_B64, **extra}
    resp = client.post("/v1/jobs", json=body, headers=auth)
    assert resp.status_code == 202, resp.text
    return resp.json()["id"]


def _run_worker() -> None:
    sm = get_sessionmaker()
    with sm() as session:
        assert process_one(session, "test-worker") is True


def test_canvas_annotate_end_to_end(client, auth, agent_on, db):
    agent_client.set_client(FakeAnthropic(json_payload(fixture("valid-full-vocabulary"))))
    job_id = _submit(client, auth)

    # The base64 image is never echoed back; only image_key is on the request.
    queued = client.get(f"/v1/jobs/{job_id}", headers=auth).json()
    assert "image" not in queued["request"]
    assert queued["request"]["image_key"].endswith(".png")

    _run_worker()

    got = client.get(f"/v1/jobs/{job_id}", headers=auth).json()
    assert got["status"] == "done"
    assert got["result"]["contract_version"] == "agent-output/v1"
    assert got["result"]["summary"]
    assert len(got["result"]["annotations"]) == 9

    # cards rows exist (the fixture has exactly one card).
    cards = db.execute(select(Card).where(Card.job_id == job_id)).scalars().all()
    assert len(cards) == 1
    assert cards[0].kind == "task"

    # Tokens recorded on the job row.
    job = db.get(Job, job_id)
    assert job.input_tokens == 11 and job.output_tokens == 7
    assert job.coordinate_clamps == 0

    # /usage sums them.
    usage = client.get("/v1/usage", headers=auth).json()
    assert usage["jobs"] == 1
    assert usage["input_tokens"] == 11
    assert usage["output_tokens"] == 7
    assert usage["by_space"][str(job.space_id)]["input_tokens"] == 11


def test_diagram_response_with_all_nine_types_validates_and_stores(client, auth, agent_on, db):
    # A recorded diagram markup (the frozen full-vocabulary fixture) with all nine
    # annotation types round-trips through the canvas.annotate handler (Stage 9).
    agent_client.set_client(FakeAnthropic(json_payload(fixture("valid-full-vocabulary"))))
    job_id = _submit(client, auth)
    _run_worker()

    got = client.get(f"/v1/jobs/{job_id}", headers=auth).json()
    assert got["status"] == "done"
    anns = got["result"]["annotations"]
    assert {a["type"] for a in anns} == {
        "highlight",
        "arrow",
        "ellipse",
        "rect",
        "underline",
        "strikethrough",
        "path",
        "text",
        "margin_note",
    }
    # The arrow keeps its relationship label and endpoints (diagram markup).
    arrow = next(a for a in anns if a["type"] == "arrow")
    assert arrow["label"] == "blocks"
    assert arrow["from"] == [0.20, 0.40] and arrow["to"] == [0.70, 0.50]
    # The margin note keeps its down-the-page position.
    note = next(a for a in anns if a["type"] == "margin_note")
    assert note["y"] == 0.35
    # Nothing was clamped — all coordinates were already in-range.
    job = db.get(Job, job_id)
    assert job.coordinate_clamps == 0


def test_usage_zero_on_fresh(client, auth):
    usage = client.get("/v1/usage", headers=auth).json()
    assert usage == {"jobs": 0, "input_tokens": 0, "output_tokens": 0, "by_space": {}}


def test_kill_switch_off_fails_with_disabled_card(client, auth, db):
    # agent_enabled defaults to False -> immediate failure with the disabled card.
    assert get_settings().agent_enabled is False
    job_id = _submit(client, auth)
    _run_worker()

    got = client.get(f"/v1/jobs/{job_id}", headers=auth).json()
    assert got["status"] == "failed"
    cards = db.execute(select(Card).where(Card.job_id == job_id)).scalars().all()
    assert len(cards) == 1
    assert cards[0].kind == "error"
    assert cards[0].title == "Agent disabled"


def test_two_validation_failures_fail_with_one_error_card(client, auth, agent_on, db):
    bad = json_payload(
        {
            "summary": "pixels",
            "annotations": [{"id": "a1", "type": "rect", "x": 1.2, "y": 0.1, "w": 0.5, "h": 0.5}],
            "cards": [],
            "brain_writes": [],
        }
    )
    agent_client.set_client(FakeAnthropic([bad, bad]))
    job_id = _submit(client, auth)
    _run_worker()

    got = client.get(f"/v1/jobs/{job_id}", headers=auth).json()
    assert got["status"] == "failed"
    cards = db.execute(select(Card).where(Card.job_id == job_id)).scalars().all()
    assert len(cards) == 1  # exactly one error card
    assert cards[0].kind == "error"
    # Tokens were still recorded (the agent was actually called twice).
    job = db.get(Job, job_id)
    assert job.input_tokens == 22 and job.output_tokens == 14


def test_daily_cap_fails_with_limit_card(client, auth, agent_on, db, monkeypatch):
    monkeypatch.setattr(get_settings(), "agent_daily_cap", 0)
    agent_client.set_client(FakeAnthropic(json_payload(fixture("valid-minimal"))))
    job_id = _submit(client, auth)
    _run_worker()

    got = client.get(f"/v1/jobs/{job_id}", headers=auth).json()
    assert got["status"] == "failed"
    cards = db.execute(select(Card).where(Card.job_id == job_id)).scalars().all()
    assert len(cards) == 1
    assert cards[0].title == "Daily limit reached"


def test_annotate_requires_image(client, auth):
    resp = client.post("/v1/jobs", json={"type": "canvas.annotate"}, headers=auth)
    assert resp.status_code == 422
    assert resp.json()["error"]["code"] == "validation"


def test_oversize_image_rejected_413(client, auth):
    import base64

    big = base64.b64encode(b"\x00" * (2 * 1024 * 1024 + 1)).decode()
    resp = client.post("/v1/jobs", json={"type": "canvas.annotate", "image": big}, headers=auth)
    assert resp.status_code == 413
