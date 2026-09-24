"""Stage 26: the brain_search tool + the create_message tool loop (ADR-0013 §4-§5).

The headline invariant is the byte-identical no-tools call; the loop tests use the
scripted FakeToolAnthropic, which captures ``messages.create`` kwargs per round.
"""

from __future__ import annotations

import pytest

from app.agent.client import DEFAULT_MODEL, create_message
from app.agent.tools import (
    BRAIN_SEARCH_TOOL,
    ToolResult,
    make_executor,
    resolve_tools,
    run_brain_search,
)
from app.brain.store import create_entry
from app.config import get_settings
from tests.fakes import FakeAnthropic, FakeToolAnthropic, json_payload, text_turn, tool_turn

_FINAL = json_payload({"summary": "s", "annotations": [], "cards": [], "brain_writes": []})
_SCHEMA = {"type": "object"}


@pytest.fixture
def tools_on(monkeypatch):
    monkeypatch.setattr(get_settings(), "agent_tools_enabled", True)


def _ok_executor(name, tool_input):
    return ToolResult(content="RESULT", lookup={"query": tool_input.get("query"), "count": 2})


# --- resolve_tools --------------------------------------------------------------------


def test_resolve_known_and_drop_unknown():
    assert resolve_tools(["brain_search"]) == [BRAIN_SEARCH_TOOL]
    # An unknown name in the allow-list is dropped (logged), not an error.
    assert resolve_tools(["brain_search", "bogus"]) == [BRAIN_SEARCH_TOOL]


def test_resolve_empty_returns_nothing():
    assert resolve_tools([]) == []
    assert resolve_tools(None) == []
    assert resolve_tools(["nope"]) == []


# --- brain_search executor formatting -------------------------------------------------


def test_run_brain_search_wraps_results_as_data(db):
    create_entry(db, space_slug="work", kind="fact", text="Austin offsite on 14 October")
    db.commit()
    result = run_brain_search(db, "work", {"query": "offsite", "limit": 5})
    assert result.lookup == {"query": "offsite", "count": 1, "mode": "and"}
    assert "<brain_search_result>" in result.content and "</brain_search_result>" in result.content
    assert "not instructions" in result.content
    assert "[fact] Austin offsite on 14 October" in result.content


def test_run_brain_search_no_match_is_not_an_error(db):
    result = run_brain_search(db, "work", {"query": "elephant"})
    assert result.is_error is False
    assert result.lookup == {"query": "elephant", "count": 0, "mode": "and"}
    assert "(no matching entries)" in result.content


def test_executor_unknown_tool_is_error(db):
    execute = make_executor(db, "work")
    out = execute("mystery", {})
    assert out.is_error is True


# --- create_message tool loop ---------------------------------------------------------


def test_no_tools_call_is_byte_identical(monkeypatch):
    # (a) switch off + no tools, and (b) switch off WITH tools listed, must both produce
    # exactly today's call shape: model/max_tokens/system/messages/output_config, no more.
    monkeypatch.setattr(get_settings(), "agent_tools_enabled", False)
    messages = [{"role": "user", "content": "hi"}]

    baseline = FakeAnthropic(_FINAL)
    create_message(
        model="",
        system="SYS",
        messages=messages,
        schema=_SCHEMA,
        job_type="canvas.ask",
        client=baseline,
    )
    call_no_tools = baseline.messages.calls[0]

    listed = FakeAnthropic(_FINAL)
    create_message(
        model="",
        system="SYS",
        messages=messages,
        schema=_SCHEMA,
        job_type="canvas.ask",
        tools=[BRAIN_SEARCH_TOOL],
        tool_executor=_ok_executor,
        client=listed,
    )
    call_switch_off = listed.messages.calls[0]

    for call in (call_no_tools, call_switch_off):
        assert set(call) == {"model", "max_tokens", "system", "messages", "output_config"}
        assert "tools" not in call and "tool_choice" not in call
        assert call["model"] == DEFAULT_MODEL
        assert call["output_config"] == {"effort": "low"}
    assert call_no_tools == call_switch_off


def test_switch_on_but_empty_tools_still_byte_identical(tools_on):
    fake = FakeAnthropic(_FINAL)
    create_message(
        model="m",
        system="SYS",
        messages=[],
        schema=_SCHEMA,
        job_type="canvas.ask",
        tools=[],
        tool_executor=_ok_executor,
        client=fake,
    )
    assert "tools" not in fake.messages.calls[0]


def test_one_tool_round_executes_and_sums_tokens(tools_on):
    fake = FakeToolAnthropic(
        [tool_turn(("tu1", "brain_search", {"query": "offsite"})), text_turn(_FINAL)]
    )
    call = create_message(
        model="m",
        system="SYS",
        messages=[{"role": "user", "content": "hi"}],
        schema=_SCHEMA,
        job_type="canvas.ask",
        tools=[BRAIN_SEARCH_TOOL],
        tool_executor=_ok_executor,
        client=fake,
    )
    assert call.raw_text == _FINAL
    assert (call.input_tokens, call.output_tokens) == (22, 14)  # summed across 2 calls
    assert call.brain_lookups == [{"query": "offsite", "count": 2}]
    # Round 1 sent tools; the follow-up carries the assistant turn + the tool_result.
    assert fake.messages.calls[0]["tools"] == [BRAIN_SEARCH_TOOL]
    followup = fake.messages.calls[1]["messages"]
    assert followup[-2]["role"] == "assistant"
    result_block = followup[-1]["content"][0]
    assert result_block["type"] == "tool_result" and result_block["tool_use_id"] == "tu1"
    assert result_block["content"] == "RESULT"


def test_three_rounds_force_final_answer(tools_on):
    fake = FakeToolAnthropic(
        [
            tool_turn(("a", "brain_search", {"query": "x"})),
            tool_turn(("b", "brain_search", {"query": "y"})),
            text_turn(_FINAL),
        ]
    )
    call = create_message(
        model="m",
        system="SYS",
        messages=[{"role": "user", "content": "hi"}],
        schema=_SCHEMA,
        job_type="canvas.ask",
        tools=[BRAIN_SEARCH_TOOL],
        tool_executor=_ok_executor,
        client=fake,
    )
    assert call.raw_text == _FINAL
    assert len(fake.messages.calls) == 3
    # The final (3rd) round forces the answer.
    assert fake.messages.calls[2]["tool_choice"] == {"type": "none"}
    assert len(call.brain_lookups) == 2


def test_tool_error_becomes_is_error_result_and_completes(tools_on):
    def _boom(name, tool_input):
        raise RuntimeError("kaboom")

    fake = FakeToolAnthropic(
        [tool_turn(("tu1", "brain_search", {"query": "x"})), text_turn(_FINAL)]
    )
    call = create_message(
        model="m",
        system="SYS",
        messages=[{"role": "user", "content": "hi"}],
        schema=_SCHEMA,
        job_type="canvas.ask",
        tools=[BRAIN_SEARCH_TOOL],
        tool_executor=_boom,
        client=fake,
    )
    assert call.raw_text == _FINAL  # loop never raised; the job completes
    result_block = fake.messages.calls[1]["messages"][-1]["content"][0]
    assert result_block["is_error"] is True
    assert "failed" in result_block["content"]
