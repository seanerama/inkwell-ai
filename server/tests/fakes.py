"""Shared test doubles for the agent runtime: a fake anthropic client + a tiny PNG."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

# A valid 1x1 PNG. The fake client ignores the image, but the API decodes and stores it.
PNG_1X1_B64 = (
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR4"
    "2mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
)


def repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def fixture(name: str) -> dict:
    path = repo_root() / "contracts" / "fixtures" / "agent-output" / f"{name}.json"
    data = json.loads(path.read_text())
    data.pop("_reject_reason", None)
    return data


class _Block:
    def __init__(self, text: str) -> None:
        self.type = "text"
        self.text = text


class _ToolUseBlock:
    def __init__(self, block_id: str, name: str, tool_input: dict) -> None:
        self.type = "tool_use"
        self.id = block_id
        self.name = name
        self.input = tool_input


class _Usage:
    def __init__(self, input_tokens: int, output_tokens: int) -> None:
        self.input_tokens = input_tokens
        self.output_tokens = output_tokens


class _Response:
    def __init__(self, text: str, input_tokens: int, output_tokens: int) -> None:
        self.content = [_Block(text)]
        self.stop_reason = "end_turn"
        self.usage = _Usage(input_tokens, output_tokens)


class _Messages:
    def __init__(self, payloads: list[str], tokens: tuple[int, int]) -> None:
        self._payloads = payloads
        self._tokens = tokens
        self.calls: list[dict[str, Any]] = []

    def create(self, **kwargs: Any) -> _Response:
        self.calls.append(kwargs)
        idx = min(len(self.calls) - 1, len(self._payloads) - 1)
        return _Response(self._payloads[idx], *self._tokens)


class FakeAnthropic:
    """A stand-in for ``anthropic.Anthropic`` returning canned JSON text per call.

    ``payloads`` are the raw text bodies returned in order (the last repeats if more
    calls are made). ``tokens`` is the ``(input, output)`` usage reported on every call.
    """

    def __init__(self, payloads: list[str] | str, tokens: tuple[int, int] = (11, 7)) -> None:
        if isinstance(payloads, str):
            payloads = [payloads]
        self.messages = _Messages(payloads, tokens)


# --- Scripted tool-use fake (Stage 26) -------------------------------------------------


class _ScriptResponse:
    def __init__(self, content: list, stop_reason: str, tokens: tuple[int, int]) -> None:
        self.content = content
        self.stop_reason = stop_reason
        self.usage = _Usage(*tokens)


def text_turn(text: str) -> dict:
    """A scripted turn that ends with a final text answer."""
    return {"blocks": [_Block(text)], "stop_reason": "end_turn"}


def tool_turn(*calls: tuple[str, str, dict]) -> dict:
    """A scripted turn that emits one or more ``tool_use`` blocks (id, name, input)."""
    blocks = [_ToolUseBlock(cid, name, tool_input) for cid, name, tool_input in calls]
    return {"blocks": blocks, "stop_reason": "tool_use"}


class _ScriptMessages:
    def __init__(self, turns: list[dict], tokens: tuple[int, int]) -> None:
        self._turns = turns
        self._tokens = tokens
        self.calls: list[dict[str, Any]] = []

    def create(self, **kwargs: Any) -> _ScriptResponse:
        self.calls.append(kwargs)
        idx = min(len(self.calls) - 1, len(self._turns) - 1)
        turn = self._turns[idx]
        return _ScriptResponse(turn["blocks"], turn["stop_reason"], self._tokens)


class FakeToolAnthropic:
    """A scripted fake for the tool loop: each turn is ``text_turn``/``tool_turn``.

    Turns are returned in order (the last repeats). Captures ``messages.create`` kwargs
    in ``self.messages.calls`` so tests can assert the request shape per round.
    """

    def __init__(self, turns: list[dict], tokens: tuple[int, int] = (11, 7)) -> None:
        self.messages = _ScriptMessages(turns, tokens)


def json_payload(data: dict) -> str:
    return json.dumps(data)
