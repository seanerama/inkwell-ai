"""Card routes + the ``cards`` field on ``JobOut`` (Stage 10, additive device-api v1).

Mirrors ``test_canvas_ask.py`` idioms (FakeAnthropic, json_payload, process_one, the
``agent_on`` fixture, ``client``/``auth``/``db``). A done ``canvas.ask`` job seeds one
``answer`` card via the worker; the PATCH/action routes then drive its state, and a
state change is shown to re-appear through ``/sync`` with a cursor taken before it.
"""

from __future__ import annotations

import pytest
from sqlalchemy import select

from app.agent import client as agent_client
from app.config import get_settings
from app.db.base import get_sessionmaker
from app.db.models import Card
from app.jobs.queue import process_one
from tests.fakes import PNG_1X1_B64, FakeAnthropic, json_payload

# An ask response carrying one `answer` card with both a `confirm` and a `reject` action,
# plus a `save_to_brain` action whose kind is not yet implemented.
ASK_RESPONSE = {
    "summary": "Answered the question and offered follow-up actions.",
    "annotations": [
        {"id": "t1", "type": "text", "at": [0.42, 0.18], "text": "10", "size": 0.02},
    ],
    "cards": [
        {
            "kind": "answer",
            "title": "1 + 9 = 10",
            "body": "**10**. Nine plus one is ten.",
            "anchors": [{"annotation_id": "t1"}],
            "actions": [
                {"id": "c1", "label": "Looks right", "kind": "confirm", "payload": {}},
                {"id": "c2", "label": "Not helpful", "kind": "reject", "payload": {}},
                {
                    "id": "c3",
                    "label": "Save as task",
                    "kind": "save_to_brain",
                    "payload": {"kind": "task"},
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


def _submit(client, auth, **extra) -> str:
    body = {"type": "canvas.ask", "image": PNG_1X1_B64, **extra}
    resp = client.post("/v1/jobs", json=body, headers=auth)
    assert resp.status_code == 202, resp.text
    return resp.json()["id"]


def _run_worker() -> None:
    sm = get_sessionmaker()
    with sm() as session:
        assert process_one(session, "test-worker") is True


def _seed_card(client, auth, agent_on) -> tuple[str, str]:
    """Run a done ask job; return ``(job_id, card_id)`` for its one answer card."""
    fake = FakeAnthropic(json_payload(ASK_RESPONSE))
    agent_client.set_client(fake)
    job_id = _submit(client, auth)
    _run_worker()
    got = client.get(f"/v1/jobs/{job_id}", headers=auth).json()
    assert got["status"] == "done"
    assert len(got["cards"]) == 1
    return job_id, got["cards"][0]["id"]


def test_cards_present_on_jobout_after_done_job(client, auth, agent_on):
    job_id, card_id = _seed_card(client, auth, agent_on)
    got = client.get(f"/v1/jobs/{job_id}", headers=auth).json()
    card = got["cards"][0]
    assert card["id"] == card_id
    assert card["kind"] == "answer"
    assert card["title"] == "1 + 9 = 10"
    assert card["body"] == "**10**. Nine plus one is ten."
    assert card["state"] == "open"
    assert card["anchors"][0]["annotation_id"] == "t1"
    assert card["actions"][0]["kind"] == "confirm"
    assert "created_at" in card


def test_patch_open_to_done(client, auth, agent_on):
    _, card_id = _seed_card(client, auth, agent_on)
    resp = client.patch(f"/v1/cards/{card_id}", json={"state": "done"}, headers=auth)
    assert resp.status_code == 200, resp.text
    assert resp.json()["state"] == "done"


def test_patch_open_to_dismissed(client, auth, agent_on):
    _, card_id = _seed_card(client, auth, agent_on)
    resp = client.patch(f"/v1/cards/{card_id}", json={"state": "dismissed"}, headers=auth)
    assert resp.status_code == 200, resp.text
    assert resp.json()["state"] == "dismissed"


def test_patch_done_to_dismissed_is_conflict(client, auth, agent_on):
    _, card_id = _seed_card(client, auth, agent_on)
    assert (
        client.patch(f"/v1/cards/{card_id}", json={"state": "done"}, headers=auth).status_code
        == 200
    )
    resp = client.patch(f"/v1/cards/{card_id}", json={"state": "dismissed"}, headers=auth)
    assert resp.status_code == 409
    assert resp.json()["error"]["code"] == "conflict"


def test_patch_unknown_card_is_404(client, auth):
    import uuid

    resp = client.patch(f"/v1/cards/{uuid.uuid4()}", json={"state": "done"}, headers=auth)
    assert resp.status_code == 404
    assert resp.json()["error"]["code"] == "not_found"


def test_patch_bad_state_is_422(client, auth, agent_on):
    _, card_id = _seed_card(client, auth, agent_on)
    resp = client.patch(f"/v1/cards/{card_id}", json={"state": "banana"}, headers=auth)
    assert resp.status_code == 422
    assert resp.json()["error"]["code"] == "validation"


def test_patch_missing_state_is_422(client, auth, agent_on):
    _, card_id = _seed_card(client, auth, agent_on)
    resp = client.patch(f"/v1/cards/{card_id}", json={}, headers=auth)
    assert resp.status_code == 422
    assert resp.json()["error"]["code"] == "validation"


def test_action_confirm_sets_done(client, auth, agent_on):
    _, card_id = _seed_card(client, auth, agent_on)
    resp = client.post(f"/v1/cards/{card_id}/actions/c1", headers=auth)
    assert resp.status_code == 200, resp.text
    assert resp.json()["state"] == "done"


def test_action_reject_sets_dismissed(client, auth, agent_on):
    _, card_id = _seed_card(client, auth, agent_on)
    resp = client.post(f"/v1/cards/{card_id}/actions/c2", headers=auth)
    assert resp.status_code == 200, resp.text
    assert resp.json()["state"] == "dismissed"


def test_action_save_to_brain_is_not_implemented(client, auth, agent_on):
    _, card_id = _seed_card(client, auth, agent_on)
    resp = client.post(f"/v1/cards/{card_id}/actions/c3", headers=auth)
    assert resp.status_code == 422
    assert resp.json()["error"]["code"] == "not_implemented"


def test_action_unknown_id_is_404(client, auth, agent_on):
    _, card_id = _seed_card(client, auth, agent_on)
    resp = client.post(f"/v1/cards/{card_id}/actions/nope", headers=auth)
    assert resp.status_code == 404
    assert resp.json()["error"]["code"] == "not_found"


def test_action_on_unknown_card_is_404(client, auth):
    import uuid

    resp = client.post(f"/v1/cards/{uuid.uuid4()}/actions/c1", headers=auth)
    assert resp.status_code == 404
    assert resp.json()["error"]["code"] == "not_found"


def test_card_state_change_bumps_updated_at_and_reappears_through_sync(client, auth, agent_on, db):
    job_id, card_id = _seed_card(client, auth, agent_on)

    # Take a cursor AFTER the job is done, so the job is behind the cursor.
    sync1 = client.get("/v1/sync", headers=auth).json()
    cursor = sync1["cursor"]
    ids_before = [
        j["id"] for j in client.get(f"/v1/sync?cursor={cursor}", headers=auth).json()["jobs"]
    ]
    assert job_id not in ids_before  # nothing new yet

    # Change the card state; the parent job's updated_at should bump.
    resp = client.patch(f"/v1/cards/{card_id}", json={"state": "done"}, headers=auth)
    assert resp.status_code == 200

    page = client.get(f"/v1/sync?cursor={cursor}", headers=auth).json()
    job_ids = [j["id"] for j in page["jobs"]]
    assert job_id in job_ids
    reappeared = next(j for j in page["jobs"] if j["id"] == job_id)
    assert reappeared["cards"][0]["state"] == "done"

    # Sanity: the DB row also reflects the change.
    card = db.execute(select(Card).where(Card.id == card_id)).scalar_one()
    assert card.state == "done"
