"""Integration: canvas.ask through the API + worker with a fake client (Stage 7).

The device sends ``canvas.ask`` with NO instruction (one-tap send); the server supplies
the default "Read this note and respond." and the ask guidance, runs the type-agnostic
handler, and a response carrying a ``text`` annotation plus an ``answer`` card
round-trips into ``jobs.result`` and ``cards`` rows.
"""

from __future__ import annotations

import pytest
from sqlalchemy import select

from app.agent import client as agent_client
from app.agent.prompt import build_instruction, build_system_prompt
from app.config import get_settings
from app.db.base import get_sessionmaker
from app.db.models import Card, Job
from app.jobs.handlers import get_job_handler
from app.jobs.queue import process_one
from tests.fakes import PNG_1X1_B64, FakeAnthropic, json_payload

# A recorded-shape canvas.ask response for the owner's acceptance note "what is 1+9=?":
# the short answer as a `text` annotation beside the question, plus one `answer` card.
ASK_RESPONSE = {
    "summary": "Answered the question on the note: 1 + 9 = 10.",
    "annotations": [
        {"id": "t1", "type": "text", "at": [0.42, 0.18], "text": "10", "size": 0.02},
    ],
    "cards": [
        {
            "kind": "answer",
            "title": "1 + 9 = 10",
            "body": "**10**. Nine plus one is ten.",
            "anchors": [{"annotation_id": "t1"}],
            "actions": [],
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


def _submit(client, auth, **extra) -> str:
    body = {"type": "canvas.ask", "image": PNG_1X1_B64, **extra}
    resp = client.post("/v1/jobs", json=body, headers=auth)
    assert resp.status_code == 202, resp.text
    return resp.json()["id"]


def _run_worker() -> None:
    sm = get_sessionmaker()
    with sm() as session:
        assert process_one(session, "test-worker") is True


def test_canvas_ask_is_accepted_202_and_queued(client, auth):
    job_id = _submit(client, auth)
    got = client.get(f"/v1/jobs/{job_id}", headers=auth).json()
    assert got["type"] == "canvas.ask"
    assert got["status"] == "queued"
    # No instruction was sent: none is stored; the image is held by key only.
    assert "instruction" not in got["request"]
    assert "image" not in got["request"]
    assert got["request"]["image_key"].endswith(".png")


def test_canvas_ask_has_a_registered_handler():
    assert get_job_handler("canvas.ask") is not None
    # The same type-agnostic handler serves both implemented types.
    assert get_job_handler("canvas.ask") is get_job_handler("canvas.annotate")


def test_canvas_ask_end_to_end_text_annotation_and_answer_card(client, auth, agent_on, db):
    fake = FakeAnthropic(json_payload(ASK_RESPONSE))
    agent_client.set_client(fake)
    job_id = _submit(client, auth)  # one-tap: no instruction at all

    _run_worker()

    got = client.get(f"/v1/jobs/{job_id}", headers=auth).json()
    assert got["status"] == "done"
    result = got["result"]
    assert result["contract_version"] == "agent-output/v1"
    assert result["summary"] == ASK_RESPONSE["summary"]
    assert len(result["annotations"]) == 1
    text = result["annotations"][0]
    assert text["type"] == "text"
    assert text["text"] == "10"
    assert text["at"] == [0.42, 0.18]
    assert text["size"] == 0.02

    # The answer card round-trips into a `cards` row with its body.
    cards = db.execute(select(Card).where(Card.job_id == job_id)).scalars().all()
    assert len(cards) == 1
    assert cards[0].kind == "answer"
    assert cards[0].title == "1 + 9 = 10"
    assert cards[0].body == "**10**. Nine plus one is ten."

    # Tokens recorded; effort was "low" and the default ask instruction was sent.
    job = db.get(Job, job_id)
    assert job.input_tokens == 11 and job.output_tokens == 7
    call = fake.messages.calls[0]
    assert call["output_config"]["effort"] == "low"
    user_blocks = call["messages"][0]["content"]
    texts = [b["text"] for b in user_blocks if b.get("type") == "text"]
    assert texts == ["Read this note and respond."]
    assert "Task: read the canvas as a note." in call["system"]


def test_canvas_ask_with_a_note_sends_the_note_as_instruction(client, auth, agent_on, db):
    fake = FakeAnthropic(json_payload(ASK_RESPONSE))
    agent_client.set_client(fake)
    job_id = _submit(client, auth, instruction="Answer in French.")
    _run_worker()

    got = client.get(f"/v1/jobs/{job_id}", headers=auth).json()
    assert got["status"] == "done"
    assert got["request"]["instruction"] == "Answer in French."
    user_blocks = fake.messages.calls[0]["messages"][0]["content"]
    texts = [b["text"] for b in user_blocks if b.get("type") == "text"]
    assert texts == ["Answer in French."]


def test_canvas_ask_requires_image(client, auth):
    resp = client.post("/v1/jobs", json={"type": "canvas.ask"}, headers=auth)
    assert resp.status_code == 422
    assert resp.json()["error"]["code"] == "validation"


def test_canvas_ask_kill_switch_off_fails_with_disabled_card(client, auth, db):
    assert get_settings().agent_enabled is False
    job_id = _submit(client, auth)
    _run_worker()
    got = client.get(f"/v1/jobs/{job_id}", headers=auth).json()
    assert got["status"] == "failed"
    cards = db.execute(select(Card).where(Card.job_id == job_id)).scalars().all()
    assert len(cards) == 1 and cards[0].kind == "error"
    assert cards[0].title == "Agent disabled"


def test_ask_guidance_present_for_ask_and_absent_for_annotate():
    ask = build_system_prompt(job_type="canvas.ask")
    annotate = build_system_prompt(job_type="canvas.annotate")
    assert "Task: read the canvas as a note." in ask
    assert "`answer` card" in ask and "`underline`" in ask
    assert "Task: read the canvas as a note." not in annotate
    assert "Task: annotate the canvas." in annotate
    assert "Task: annotate the canvas." not in ask


def test_default_instruction_per_type():
    assert build_instruction(None, "canvas.ask") == "Read this note and respond."
    assert build_instruction("", "canvas.ask") == "Read this note and respond."
    assert build_instruction("   ", "canvas.ask") == "Read this note and respond."
    assert build_instruction("Be brief.", "canvas.ask") == "Be brief."
    # canvas.annotate is unchanged.
    assert build_instruction(None, "canvas.annotate") == "Annotate this canvas."
    assert build_instruction(None, "canvas.formalize") == "Analyse this canvas."
