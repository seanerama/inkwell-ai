"""Validation + extraction + one-retry tests (SPEC §10.4, contract coordinate-mapping)."""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from app.agent import client as agent_client
from app.agent.validate import AgentValidationError, extract_json, run_agent
from app.contracts.validation import SemanticError, validate_agent_output_counted
from tests.fakes import FakeAnthropic, fixture, json_payload


@pytest.fixture(autouse=True)
def _reset_client():
    yield
    agent_client.set_client(None)


# --- Tier-1/2 fixture behaviour (contracts/fixtures/README.md) ------------------------


@pytest.mark.parametrize("name", ["valid-minimal", "valid-full-vocabulary", "valid-edge-tolerance"])
def test_valid_fixtures_accepted(name):
    ao, _clamps = validate_agent_output_counted(fixture(name))
    assert ao.summary


@pytest.mark.parametrize(
    "name",
    [
        "invalid-missing-summary",
        "invalid-unknown-type",
        "invalid-pixel-coordinates",
        "invalid-duplicate-id",
        "invalid-dangling-anchor",
    ],
)
def test_invalid_fixtures_rejected(name):
    with pytest.raises((ValidationError, SemanticError)):
        validate_agent_output_counted(fixture(name))


# --- Coordinate clamp / reject --------------------------------------------------------


def test_x_1_03_is_clamped_and_counted():
    data = {
        "summary": "clamp me",
        "annotations": [{"id": "a1", "type": "rect", "x": 1.03, "y": 0.1, "w": 0.5, "h": 0.5}],
        "cards": [],
        "brain_writes": [],
    }
    ao, clamps = validate_agent_output_counted(data)
    assert clamps == 1
    rect = ao.annotations[0].root
    assert rect.x == 1.0


def test_x_1_2_is_rejected_with_path():
    data = {
        "summary": "reject me",
        "annotations": [{"id": "a1", "type": "rect", "x": 1.2, "y": 0.1, "w": 0.5, "h": 0.5}],
        "cards": [],
        "brain_writes": [],
    }
    with pytest.raises(SemanticError) as exc:
        validate_agent_output_counted(data)
    assert exc.value.path == "annotations[0].x"


def test_points_path_format():
    data = {
        "summary": "reject me",
        "annotations": [
            {"id": "a1", "type": "highlight", "points": [[0.1, 0.1], [0.2, 0.2], [0.3, 9.9]]}
        ],
        "cards": [],
        "brain_writes": [],
    }
    with pytest.raises(SemanticError) as exc:
        validate_agent_output_counted(data)
    assert exc.value.path == "annotations[0].points[2][1]"


# --- Extraction (fence stripping, SPEC §10.4) -----------------------------------------


def test_extract_json_strips_fences():
    raw = '```json\n{"summary": "x", "annotations": [], "cards": [], "brain_writes": []}\n```'
    assert extract_json(raw)["summary"] == "x"


def test_extract_json_plain():
    assert extract_json('{"summary": "y"}')["summary"] == "y"


# --- One-retry orchestration ----------------------------------------------------------


def _run(job_type="canvas.annotate"):
    return run_agent(
        model="claude-sonnet-5",
        system_prompt="",
        brain_context="",
        image_b64="aW1n",
        instruction="Annotate this canvas.",
        job_type=job_type,
        job_id="job-1",
    )


def test_run_agent_succeeds_first_try():
    agent_client.set_client(FakeAnthropic(json_payload(fixture("valid-full-vocabulary"))))
    run = _run()
    assert run.output.summary
    assert run.input_tokens == 11 and run.output_tokens == 7
    assert run.coordinate_clamps == 0


def test_run_agent_retries_once_then_fails_with_path():
    bad = json_payload(
        {
            "summary": "pixels",
            "annotations": [{"id": "a1", "type": "rect", "x": 1.2, "y": 0.1, "w": 0.5, "h": 0.5}],
            "cards": [],
            "brain_writes": [],
        }
    )
    fake = FakeAnthropic([bad, bad])
    agent_client.set_client(fake)
    with pytest.raises(AgentValidationError) as exc:
        _run()
    # Two calls were made (initial + one retry).
    assert len(fake.messages.calls) == 2
    # The retry message list carries the offending JSON path.
    retry_messages = fake.messages.calls[1]["messages"]
    combined = " ".join(str(m.get("content")) for m in retry_messages)
    assert "annotations[0].x" in combined
    # Tokens spent across both attempts are recorded on the error for accounting.
    assert exc.value.input_tokens == 22 and exc.value.output_tokens == 14


def test_run_agent_retry_succeeds_second_try():
    bad = json_payload(
        {
            "summary": "pixels",
            "annotations": [{"id": "a1", "type": "rect", "x": 1.2, "y": 0.1, "w": 0.5, "h": 0.5}],
            "cards": [],
            "brain_writes": [],
        }
    )
    good = json_payload(fixture("valid-minimal"))
    agent_client.set_client(FakeAnthropic([bad, good]))
    run = _run()
    assert run.output.summary == "Nothing to mark up."
    assert run.input_tokens == 22  # tokens accumulate across both attempts
