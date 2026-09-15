"""The ANTHROPIC_API_KEY must never appear in a log line, even at debug (SPEC §11)."""

from __future__ import annotations

import io
import logging

import structlog

from app.agent import client as agent_client
from app.config import get_settings
from app.db.base import get_sessionmaker
from app.jobs.queue import process_one
from app.logging import redact_secrets
from tests.fakes import PNG_1X1_B64, FakeAnthropic, fixture, json_payload

SENTINEL = "sk-ant-SECRET-do-not-log-abc123"


def test_redact_secrets_processor_scrubs_the_key(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_API_KEY", SENTINEL)
    event = {"event": "call", "detail": f"key={SENTINEL} used"}
    out = redact_secrets(None, "info", event)
    assert SENTINEL not in out["detail"]
    assert "[REDACTED]" in out["detail"]


def test_redaction_end_to_end_through_the_pipeline(monkeypatch):
    """A log line carrying the key is redacted through the full structlog pipeline."""
    monkeypatch.setenv("ANTHROPIC_API_KEY", SENTINEL)
    buf = io.StringIO()
    structlog.configure(
        processors=[
            structlog.processors.add_log_level,
            redact_secrets,
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(logging.DEBUG),
        logger_factory=structlog.WriteLoggerFactory(file=buf),
        cache_logger_on_first_use=False,
    )
    try:
        log = structlog.get_logger("t")
        log.debug("leaky", api_key=SENTINEL)  # even at debug level
        log.info("also_leaky", note=f"header Bearer {SENTINEL}")
    finally:
        structlog.reset_defaults()
    contents = buf.getvalue()
    assert SENTINEL not in contents
    assert "[REDACTED]" in contents


def test_full_annotate_run_never_logs_the_key(client, auth, monkeypatch, capfd):
    monkeypatch.setenv("ANTHROPIC_API_KEY", SENTINEL)
    monkeypatch.setattr(get_settings(), "agent_enabled", True)
    agent_client.set_client(FakeAnthropic(json_payload(fixture("valid-full-vocabulary"))))
    try:
        resp = client.post(
            "/v1/jobs", json={"type": "canvas.annotate", "image": PNG_1X1_B64}, headers=auth
        )
        assert resp.status_code == 202
        sm = get_sessionmaker()
        with sm() as session:
            process_one(session, "test-worker")
    finally:
        agent_client.set_client(None)
    captured = capfd.readouterr()
    assert SENTINEL not in (captured.out + captured.err)
