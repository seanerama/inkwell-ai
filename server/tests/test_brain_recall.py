"""Stage 26 acceptance (SPEC §12 Phase 5): a fact saved from one canvas is recalled by a
later job in the same space — via baseline <brain_context> injection, and via the
brain_search tool when the space allows it and AGENT_TOOLS_ENABLED is on.
"""

from __future__ import annotations

import pytest
from sqlalchemy import select

from app.agent import client as agent_client
from app.config import get_settings
from app.db.base import get_sessionmaker
from app.db.models import BrainEntry, Job
from app.jobs.queue import process_one
from tests.fakes import (
    PNG_1X1_B64,
    FakeAnthropic,
    FakeToolAnthropic,
    json_payload,
    text_turn,
    tool_turn,
)

_FACT = "Q3 offsite is in Austin on 14 October"

_EXTRACT = {
    "summary": "Recorded the offsite.",
    "annotations": [],
    "cards": [
        {"kind": "fact", "title": "Saved to brain", "body": "ok", "anchors": [], "actions": []}
    ],
    "brain_writes": [{"kind": "fact", "text": _FACT, "tags": ["offsite"]}],
}
_ASK = {
    "summary": "Answered: the offsite is in Austin on 14 October.",
    "annotations": [],
    "cards": [
        {
            "kind": "answer",
            "title": "Austin, 14 October",
            "body": "**14 October, Austin.**",
            "anchors": [],
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


@pytest.fixture
def brain_on(monkeypatch):
    monkeypatch.setattr(get_settings(), "brain_enabled", True)


@pytest.fixture
def tools_on(monkeypatch):
    monkeypatch.setattr(get_settings(), "agent_tools_enabled", True)


def _run_worker() -> None:
    sm = get_sessionmaker()
    with sm() as session:
        assert process_one(session, "test-worker") is True


def _submit(client, auth, job_type, **extra) -> str:
    body = {"type": job_type, "image": PNG_1X1_B64, **extra}
    resp = client.post("/v1/jobs", json=body, headers=auth)
    assert resp.status_code == 202, resp.text
    return resp.json()["id"]


def _extract_fact(client, auth) -> None:
    agent_client.set_client(FakeAnthropic(json_payload(_EXTRACT)))
    _submit(client, auth, "canvas.extract")
    _run_worker()


def test_extract_then_ask_injects_the_fact_in_brain_context(client, auth, agent_on, brain_on, db):
    _extract_fact(client, auth)
    assert db.execute(select(BrainEntry)).scalar_one().text == _FACT

    # A later ask in the same space, different canvas: the Austin entry rides in the
    # <brain_context> block. Tools stay off -> byte-identical call, no brain_search.
    ask = FakeAnthropic(json_payload(_ASK))
    agent_client.set_client(ask)
    _submit(client, auth, "canvas.ask", instruction="when is the offsite?")
    _run_worker()

    system = ask.messages.calls[0]["system"]
    assert "<brain_context>" in system
    assert _FACT in system
    assert "tools" not in ask.messages.calls[0]  # switch off -> no tools sent


def test_ask_with_tools_calls_brain_search_and_records_lookup(
    client, auth, agent_on, brain_on, tools_on, db
):
    _extract_fact(client, auth)

    # With tools on, the fake model calls brain_search("offsite") then answers.
    ask = FakeToolAnthropic(
        [tool_turn(("t1", "brain_search", {"query": "offsite"})), text_turn(json_payload(_ASK))]
    )
    agent_client.set_client(ask)
    job_id = _submit(client, auth, "canvas.ask", instruction="when is the offsite?")
    _run_worker()

    got = client.get(f"/v1/jobs/{job_id}", headers=auth).json()
    assert got["status"] == "done"
    # The search reached the model as data and is recorded for traceability.
    assert got["result"]["brain_lookups"] == [{"query": "offsite", "count": 1, "mode": "and"}]
    tool_result = ask.messages.calls[1]["messages"][-1]["content"][0]
    assert _FACT in tool_result["content"]
    # The first round sent the space's resolved tools.
    assert ask.messages.calls[0]["tools"][0]["name"] == "brain_search"
    # Tokens summed across the two rounds were recorded on the job.
    job = db.get(Job, job_id)
    assert job.input_tokens == 22 and job.output_tokens == 14
