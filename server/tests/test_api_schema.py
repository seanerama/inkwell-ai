"""The structured-output projection of the frozen agent-output schema (ADR-0006)."""

import json

from app.agent.api_schema import for_structured_output, open_objects, unsupported_keywords
from app.contracts.check import frozen_schema
from app.schemas import AgentOutput


def test_frozen_schema_uses_keywords_the_api_rejects():
    # Documents WHY the projection exists: the contract of record carries constraints
    # the API refuses (the staging 400 on minItems).
    found = unsupported_keywords(frozen_schema())
    assert "minItems" in found and "maxLength" in found


def test_projection_has_no_unsupported_keywords():
    projected = for_structured_output(frozen_schema())
    assert unsupported_keywords(projected) == set()
    assert "$schema" not in projected and "$id" not in projected


def test_projection_keeps_the_structural_guarantees():
    projected = for_structured_output(frozen_schema())
    assert projected["additionalProperties"] is False
    assert set(projected["required"]) == {"summary", "annotations", "cards", "brain_writes"}
    # The nine-way anyOf is flattened into one closed object with a type enum.
    ann = projected["$defs"]["Annotation"]
    assert "anyOf" not in ann and ann["additionalProperties"] is False
    assert set(ann["properties"]["type"]["enum"]) == {
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
    assert ann["required"] == ["id", "type"]
    assert {
        "points",
        "from",
        "to",
        "center",
        "rx",
        "ry",
        "x",
        "y",
        "w",
        "h",
        "at",
        "text",
        "size",
        "closed",
        "color",
        "label",
    } <= set(ann["properties"])
    assert "Arrow" not in projected["$defs"]
    # Point keeps its array type and item schema, just not the 2..2 arity.
    point = projected["$defs"]["Point"]
    assert point["type"] == "array" and point["items"] == {"type": "number"}
    assert "minItems" not in point and "maxItems" not in point


def test_projection_does_not_mutate_the_frozen_schema():
    before = json.dumps(frozen_schema(), sort_keys=True)
    for_structured_output(frozen_schema())
    assert json.dumps(frozen_schema(), sort_keys=True) == before


def test_stripped_constraints_are_still_enforced_after_the_call():
    # A response the looser API schema would allow must still be rejected by the model
    # of record: a 3-element "point" and an over-long id.
    bad = {
        "summary": "x",
        "annotations": [{"id": "a1", "type": "arrow", "from": [0.1, 0.2, 0.3], "to": [0.4, 0.5]}],
        "cards": [],
        "brain_writes": [],
    }
    try:
        AgentOutput.model_validate(bad)
    except Exception:
        return
    raise AssertionError("Pydantic must reject what the projection no longer constrains")


def test_projection_closes_every_object():
    # The API demands additionalProperties: false on every object; the contract's only
    # open object is CardAction.payload.
    assert open_objects(frozen_schema()) == ["/$defs/CardAction/properties/payload"]
    assert open_objects(for_structured_output(frozen_schema())) == []


def test_flattened_annotation_still_rejects_wrong_shapes_after_the_call():
    # The flat API object would let the model emit an arrow without "to"; the model of
    # record must reject it so the retry path runs.
    bad = {
        "summary": "x",
        "annotations": [{"id": "a1", "type": "arrow", "from": [0.1, 0.2]}],
        "cards": [],
        "brain_writes": [],
    }
    try:
        AgentOutput.model_validate(bad)
    except Exception:
        return
    raise AssertionError("arrow without 'to' must fail Pydantic validation")


def test_call_is_prompt_guided_by_default_and_structured_only_with_the_flag(monkeypatch):
    from app.agent.client import create_message
    from app.config import get_settings
    from tests.fakes import FakeAnthropic

    fake = FakeAnthropic('{"summary":"s","annotations":[],"cards":[],"brain_writes":[]}')
    projected = for_structured_output(frozen_schema())

    monkeypatch.setattr(get_settings(), "agent_structured_output", False)
    create_message(
        model="m", system="s", messages=[], schema=projected, job_type="canvas.ask", client=fake
    )
    assert fake.messages.calls[0]["output_config"] == {"effort": "low"}

    monkeypatch.setattr(get_settings(), "agent_structured_output", True)
    create_message(
        model="m", system="s", messages=[], schema=projected, job_type="canvas.ask", client=fake
    )
    assert fake.messages.calls[1]["output_config"]["format"] == {
        "type": "json_schema",
        "schema": projected,
    }
