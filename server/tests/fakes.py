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


class _Usage:
    def __init__(self, input_tokens: int, output_tokens: int) -> None:
        self.input_tokens = input_tokens
        self.output_tokens = output_tokens


class _Response:
    def __init__(self, text: str, input_tokens: int, output_tokens: int) -> None:
        self.content = [_Block(text)]
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


def json_payload(data: dict) -> str:
    return json.dumps(data)
