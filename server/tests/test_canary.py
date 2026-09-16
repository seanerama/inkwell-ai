"""Deploy-canary tests (Stage 8): job creation, the report exit codes, and /usage.

These drive the ``app.canary.run`` helpers without a live worker: create the canary
job, run ``process_one`` inline with a recorded client, then poll/report — mirroring how
``test_canvas_ask.py`` drives the worker.
"""

from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest

from app.agent import client as agent_client
from app.blobs import get_blob_store
from app.canary.run import create_canary_job, poll_job, report, run_canary
from app.config import get_settings
from app.db.base import get_sessionmaker
from app.db.models import Job
from app.jobs.queue import process_one
from tests.fakes import PNG_1X1_B64, FakeAnthropic, json_payload

# A recorded canvas.ask response for the fixture note "what is 1+9=?": one `text`
# annotation and one `answer` card (the shape a healthy deploy proves).
ASK_RESPONSE = {
    "summary": "Answered the note: 1 + 9 = 10.",
    "annotations": [{"id": "t1", "type": "text", "at": [0.42, 0.18], "text": "10", "size": 0.02}],
    "cards": [
        {
            "kind": "answer",
            "title": "1 + 9 = 10",
            "body": "**10**.",
            "anchors": [{"annotation_id": "t1"}],
            "actions": [],
        }
    ],
    "brain_writes": [],
}
# Same annotation but NO card: a `done` job that is still a canary failure.
ASK_NO_CARD = {**ASK_RESPONSE, "cards": []}


@pytest.fixture(autouse=True)
def _reset_client():
    yield
    agent_client.set_client(None)


@pytest.fixture
def agent_on(monkeypatch):
    monkeypatch.setattr(get_settings(), "agent_enabled", True)


def _run_worker() -> None:
    sm = get_sessionmaker()
    with sm() as session:
        assert process_one(session, "canary-worker") is True


def test_create_canary_job_stores_fixture_and_marks_canary(db):
    job_id = create_canary_job(db, "work")

    job = db.get(Job, job_id)
    assert job.type == "canvas.ask"
    assert job.direction == "to_agent"
    assert job.canvas_id is None
    assert job.request["canary"] is True
    assert job.request["export"] == {"w": 1109, "h": 1568, "width_cu": 2480, "height_cu": 3508}

    key = job.request["image_key"]
    assert key.endswith(".png")
    stored = get_blob_store().open(key)
    assert stored[:8] == b"\x89PNG\r\n\x1a\n"  # the real fixture bytes landed in the blob store


def test_create_canary_job_unknown_space_raises(db):
    from app.canary.run import CanaryError

    with pytest.raises(CanaryError):
        create_canary_job(db, "does-not-exist")


def test_canary_reaches_done_and_reports_exit_0(db, agent_on):
    agent_client.set_client(FakeAnthropic(json_payload(ASK_RESPONSE)))
    job_id = create_canary_job(db, "work")

    _run_worker()

    job = poll_job(get_sessionmaker(), job_id, timeout=5, interval=0.01)
    assert job is not None and job.status == "done"
    code, lines = report(job)
    assert code == 0
    # The FINAL line is exactly the deploy-log summary (no `>>` prefix).
    assert lines[-1] == "canary ok: done, 1 annotations, 1 cards"
    assert any(line.startswith("annotation types: text") for line in lines)


def test_canary_done_without_cards_is_a_failure_exit_2(db, agent_on):
    agent_client.set_client(FakeAnthropic(json_payload(ASK_NO_CARD)))
    job_id = create_canary_job(db, "work")

    _run_worker()

    job = poll_job(get_sessionmaker(), job_id, timeout=5, interval=0.01)
    assert job is not None and job.status == "done"
    code, lines = report(job)
    assert code == 2
    assert any("need >=1 of each" in line for line in lines)


def test_canary_failed_reports_exit_2_with_error(db, agent_on):
    # A response that fails validation twice -> the job transitions to `failed`.
    bad = json_payload(
        {
            "summary": "x",
            "annotations": [{"id": "a1", "type": "rect", "x": 1.2, "y": 0.1, "w": 0.5, "h": 0.5}],
            "cards": [],
            "brain_writes": [],
        }
    )
    agent_client.set_client(FakeAnthropic([bad, bad]))
    job_id = create_canary_job(db, "work")

    _run_worker()

    job = poll_job(get_sessionmaker(), job_id, timeout=5, interval=0.01)
    assert job is not None and job.status == "failed"
    code, lines = report(job)
    assert code == 2
    assert lines[0] == "status: failed"
    assert any(line.startswith("error:") for line in lines)


def test_canary_timeout_reports_exit_3(db):
    # No worker ever runs the job; a ~0 timeout returns None -> exit 3.
    job_id = create_canary_job(db, "work")
    job = poll_job(get_sessionmaker(), job_id, timeout=0, interval=0.01)
    assert job is None
    code, lines = report(job)
    assert code == 3
    assert any("timeout" in line for line in lines)


def test_run_canary_unknown_space_exits_nonzero(capsys):
    code = run_canary(space="does-not-exist", timeout=0, interval=0.01)
    assert code == 2
    assert "canary error" in capsys.readouterr().err


def test_usage_excludes_canary_jobs(client, auth, agent_on, db):
    agent_client.set_client(FakeAnthropic(json_payload(ASK_RESPONSE)))

    # One normal agent job (through the API) -> counted.
    resp = client.post("/v1/jobs", json={"type": "canvas.ask", "image": PNG_1X1_B64}, headers=auth)
    assert resp.status_code == 202, resp.text
    _run_worker()

    # One canary job -> excluded despite recording tokens.
    create_canary_job(db, "work")
    _run_worker()

    usage = client.get("/v1/usage", headers=auth).json()
    assert usage["jobs"] == 1
    assert usage["input_tokens"] == 11
    assert usage["output_tokens"] == 7


def _load_generator():
    path = Path(__file__).resolve().parents[1] / "scripts" / "make_canary_fixture.py"
    spec = importlib.util.spec_from_file_location("make_canary_fixture", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_fixture_generator_is_deterministic_and_matches_committed():
    pytest.importorskip("PIL")
    gen = _load_generator()

    first = gen.render_png()
    second = gen.render_png()
    assert first == second  # same bytes on two runs
    assert first[:8] == b"\x89PNG\r\n\x1a\n"

    fixture = Path(__file__).resolve().parents[1] / "app" / "canary" / "fixture.png"
    assert first == fixture.read_bytes()  # the committed PNG is up to date with the generator
