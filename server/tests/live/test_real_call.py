"""Live end-to-end deploy-canary test — NOT a CI gate.

Runs only when ``INKWELL_LIVE_TESTS=1`` and ``ANTHROPIC_API_KEY`` are both set. It drives
``inkwell canary`` against the REAL Anthropic API: it creates the canary job (bundled
fixture note "what is 1+9=?"), runs the worker once inline with the real client (no
worker loop runs in the test process), then asserts the canary report exits 0 with the
job ``done`` and >=1 annotation and >=1 card — the exact condition a deploy enforces.

Run by the Tester before Phase 2 merges.
"""

from __future__ import annotations

import os

import pytest

from app.agent import client as agent_client
from app.canary.run import create_canary_job, poll_job, report
from app.config import get_settings
from app.db.base import get_sessionmaker
from app.jobs.queue import process_one

LIVE = os.environ.get("INKWELL_LIVE_TESTS") == "1" and bool(os.environ.get("ANTHROPIC_API_KEY"))

pytestmark = pytest.mark.skipif(
    not LIVE, reason="live test: set INKWELL_LIVE_TESTS=1 and ANTHROPIC_API_KEY"
)


def test_canary_end_to_end_against_real_api(monkeypatch):
    monkeypatch.setattr(get_settings(), "agent_enabled", True)
    agent_client.set_client(None)  # use the real Anthropic client

    sm = get_sessionmaker()
    with sm() as session:
        job_id = create_canary_job(session, "work")

    # No worker loop runs here; process the single queued canary job inline.
    with sm() as session:
        assert process_one(session, "live-canary") is True

    job = poll_job(sm, job_id, timeout=5, interval=0.01)
    code, lines = report(job)
    for line in lines:
        print(line)

    assert job is not None and job.status == "done", lines
    assert code == 0, lines
    assert len(job.result["annotations"]) >= 1
    assert len(job.result["cards"]) >= 1
    assert lines[-1].startswith("canary ok: done,")
