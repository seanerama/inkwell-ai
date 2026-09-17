"""Stage 13: a job's system prompt carries its own space's prompt (SPEC §10.3).

The worker already passes ``space.system_prompt`` into ``build_system_prompt`` (Stage 5).
With distinct default prompts, a ``canvas.ask`` job in ``learning`` must send Learning's
prompt and one in ``work`` must send Work's — proven via the fake client's recorded
``system`` string. ``tools`` never reaches the agent call.
"""

from __future__ import annotations

import pytest
from sqlalchemy import select

from app.agent import client as agent_client
from app.config import get_settings
from app.db.base import get_sessionmaker
from app.db.models import Space
from app.jobs.queue import process_one
from tests.fakes import PNG_1X1_B64, FakeAnthropic, json_payload

ASK_RESPONSE = {
    "summary": "Answered.",
    "annotations": [{"id": "t1", "type": "text", "at": [0.4, 0.2], "text": "ok", "size": 0.02}],
    "cards": [
        {
            "kind": "answer",
            "title": "ok",
            "body": "ok.",
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


def _submit_and_run(client, auth, space_id: str) -> None:
    body = {"type": "canvas.ask", "image": PNG_1X1_B64, "space_id": space_id}
    resp = client.post("/v1/jobs", json=body, headers=auth)
    assert resp.status_code == 202, resp.text
    sm = get_sessionmaker()
    with sm() as session:
        assert process_one(session, "test-worker") is True


def test_learning_and_work_jobs_send_their_own_space_prompt(client, auth, db, agent_on):
    learning = db.execute(select(Space).where(Space.slug == "learning")).scalar_one()
    work = db.execute(select(Space).where(Space.slug == "work")).scalar_one()

    fake = FakeAnthropic(json_payload(ASK_RESPONSE))
    agent_client.set_client(fake)

    _submit_and_run(client, auth, str(learning.id))  # call 0
    _submit_and_run(client, auth, str(work.id))  # call 1

    learning_call = fake.messages.calls[0]
    work_call = fake.messages.calls[1]

    assert learning.system_prompt in learning_call["system"]
    assert work.system_prompt in work_call["system"]
    # The two agents are recognizably different.
    assert learning_call["system"] != work_call["system"]
    assert work.system_prompt not in learning_call["system"]

    # `tools` is never part of the agent call (SPEC §10.2 / this stage).
    assert "tools" not in learning_call
    assert "tools" not in work_call
