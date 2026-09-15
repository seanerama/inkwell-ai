"""The Anthropic Messages API call for the agent runtime (ADR-0006).

SDK usage here was written against the ``claude-api`` skill's Python reference (anthropic
SDK, structured outputs), not from memory:

- Structured outputs use ``output_config={"format": {"type": "json_schema", "schema":
  <frozen agent-output schema>}, "effort": <per job type>}`` on
  ``client.messages.create(...)``. With a format set, the first ``text`` content block
  is guaranteed to be valid JSON for the schema.
- Adaptive thinking is left at the API default (Sonnet 5 runs adaptive without a
  ``thinking`` argument), so we pass none.
- ``max_tokens`` is 16000 (config ``agent_max_tokens``); the call is non-streaming
  inside a worker (SPEC §10.1).
- The user turn is a base64 PNG image block followed by the instruction text block
  (SPEC §10.2).
- Token usage is read from ``response.usage.input_tokens`` / ``.output_tokens``.

The ``ANTHROPIC_API_KEY`` is read by the SDK straight from the environment; it is never
passed through config or logged (SPEC §11).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from app.config import get_settings

DEFAULT_MODEL = "claude-sonnet-5"

# output_config.effort per job type (ADR-0006). Only canvas.annotate runs this stage.
EFFORT_BY_JOB_TYPE: dict[str, str] = {
    "canvas.ask": "low",
    "canvas.extract": "low",
    "canvas.annotate": "medium",
    "canvas.formalize": "high",
    "canvas.action": "medium",
}
DEFAULT_EFFORT = "medium"

# Process-wide client, lazily constructed. Tests inject a fake via set_client().
_client: Any | None = None


def effort_for(job_type: str) -> str:
    return EFFORT_BY_JOB_TYPE.get(job_type, DEFAULT_EFFORT)


def get_client() -> Any:
    """Return the process-wide Anthropic client (reads ANTHROPIC_API_KEY from env)."""
    global _client
    if _client is None:
        import anthropic

        _client = anthropic.Anthropic()
    return _client


def set_client(client: Any | None) -> None:
    """Inject (or reset) the client. Used by tests to supply a recorded/fake client."""
    global _client
    _client = client


def image_block(image_b64: str) -> dict:
    return {
        "type": "image",
        "source": {"type": "base64", "media_type": "image/png", "data": image_b64},
    }


def text_block(text: str) -> dict:
    return {"type": "text", "text": text}


@dataclass
class AgentCall:
    """The structured object returned by the model plus token accounting."""

    raw_text: str
    input_tokens: int
    output_tokens: int


def _first_text(response: Any) -> str:
    for block in getattr(response, "content", []) or []:
        if getattr(block, "type", None) == "text":
            return block.text
    raise ValueError("model response contained no text block")


def create_message(
    *,
    model: str,
    system: str,
    messages: list[dict],
    schema: dict,
    job_type: str,
    client: Any | None = None,
) -> AgentCall:
    """Make one structured-output Messages API call and return text + token usage."""
    settings = get_settings()
    client = client or get_client()
    response = client.messages.create(
        model=model or DEFAULT_MODEL,
        max_tokens=settings.agent_max_tokens,
        system=system,
        messages=messages,
        output_config={
            "format": {"type": "json_schema", "schema": schema},
            "effort": effort_for(job_type),
        },
    )
    usage = getattr(response, "usage", None)
    return AgentCall(
        raw_text=_first_text(response),
        input_tokens=int(getattr(usage, "input_tokens", 0) or 0),
        output_tokens=int(getattr(usage, "output_tokens", 0) or 0),
    )
