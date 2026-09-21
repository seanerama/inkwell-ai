"""The Anthropic Messages API call for the agent runtime (ADR-0006).

SDK usage here was written against the ``claude-api`` skill's Python reference (anthropic
SDK, structured outputs), not from memory:

- Prompt-guided JSON by default (ADR-0006 amendment 2026-09-16); with
  ``AGENT_STRUCTURED_OUTPUT`` on, ``output_config={"format": {"type": "json_schema",
  "schema": <projected agent-output schema>}, "effort": <per job type>}`` on
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

from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

from app.config import get_settings

DEFAULT_MODEL = "claude-sonnet-5"

# output_config.effort per job type (ADR-0006). Stage 26 sets canvas.extract to medium
# (SPEC §7): "remember" reads the whole canvas and must not skimp on recall.
EFFORT_BY_JOB_TYPE: dict[str, str] = {
    "canvas.ask": "low",
    "canvas.extract": "medium",
    "canvas.annotate": "medium",
    "canvas.formalize": "high",
    "canvas.action": "medium",
}
DEFAULT_EFFORT = "medium"

# Stage 26 tool loop: at most this many model calls may see tools. The final
# (``_MAX_TOOL_ROUNDS``-th) call is forced to answer with tool_choice={"type": "none"}.
_MAX_TOOL_ROUNDS = 3

# A tool executor: (name, input) -> an object with .content / .is_error / .lookup
# (app.agent.tools.ToolResult). Kept structural so client.py need not import tools.py.
ToolExecutor = Callable[[str, dict], Any]

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
    # Stage 26: one {"query", "count"} per brain_search executed during this call's tool
    # loop (empty on the byte-identical no-tools path). Surfaced as result.brain_lookups.
    brain_lookups: list[dict] = field(default_factory=list)


def _usage_tokens(response: Any) -> tuple[int, int]:
    usage = getattr(response, "usage", None)
    return (
        int(getattr(usage, "input_tokens", 0) or 0),
        int(getattr(usage, "output_tokens", 0) or 0),
    )


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
    tools: list[dict] | None = None,
    tool_executor: ToolExecutor | None = None,
    client: Any | None = None,
) -> AgentCall:
    """Make the Messages API call(s) and return the final text + summed token usage.

    With no resolved ``tools`` (or ``AGENT_TOOLS_ENABLED`` off), this is exactly the
    single, byte-identical call it has always been — no ``tools``/``tool_choice`` keys.
    When tools ARE sent, it runs the tool loop (ADR-0013 §5): while the model stops with
    ``tool_use`` and fewer than ``_MAX_TOOL_ROUNDS`` rounds have run, it executes each
    ``tool_use`` block, appends the assistant turn and the ``tool_result`` blocks, and
    calls again; the final round forces ``tool_choice={"type": "none"}``. A tool error
    becomes a ``tool_result`` with ``is_error: true`` — it never raises out of the loop.
    """
    settings = get_settings()
    client = client or get_client()
    # ADR-0006 amendment: prompt-guided JSON by default; the schema projection is sent
    # as output_config.format only when AGENT_STRUCTURED_OUTPUT is on.
    output_config: dict[str, Any] = {"effort": effort_for(job_type)}
    if settings.agent_structured_output:
        output_config["format"] = {"type": "json_schema", "schema": schema}

    use_tools = bool(tools) and settings.agent_tools_enabled

    # Byte-identical no-tools path: the exact call shape prior stages recorded.
    if not use_tools:
        response = client.messages.create(
            model=model or DEFAULT_MODEL,
            max_tokens=settings.agent_max_tokens,
            system=system,
            messages=messages,
            output_config=output_config,
        )
        input_tokens, output_tokens = _usage_tokens(response)
        return AgentCall(
            raw_text=_first_text(response),
            input_tokens=input_tokens,
            output_tokens=output_tokens,
        )

    # Tool loop. Work on a private copy so retries in the caller stay clean.
    convo: list[dict] = list(messages)
    total_in = 0
    total_out = 0
    lookups: list[dict] = []

    for round_index in range(_MAX_TOOL_ROUNDS):
        kwargs: dict[str, Any] = {
            "model": model or DEFAULT_MODEL,
            "max_tokens": settings.agent_max_tokens,
            "system": system,
            "messages": convo,
            "output_config": output_config,
            "tools": tools,
        }
        # Final permitted round: force the answer (no more tool calls).
        if round_index == _MAX_TOOL_ROUNDS - 1:
            kwargs["tool_choice"] = {"type": "none"}

        response = client.messages.create(**kwargs)
        in_tok, out_tok = _usage_tokens(response)
        total_in += in_tok
        total_out += out_tok

        if getattr(response, "stop_reason", None) != "tool_use":
            return AgentCall(
                raw_text=_first_text(response),
                input_tokens=total_in,
                output_tokens=total_out,
                brain_lookups=lookups,
            )

        tool_uses = [b for b in (response.content or []) if getattr(b, "type", None) == "tool_use"]
        convo.append({"role": "assistant", "content": response.content})
        results: list[dict] = []
        for block in tool_uses:
            result_block: dict[str, Any] = {"type": "tool_result", "tool_use_id": block.id}
            try:
                outcome = tool_executor(block.name, dict(block.input or {}))  # type: ignore[misc]
            except Exception as exc:  # never raise out of the loop (ADR-0013 §5)
                result_block["content"] = f"tool execution failed: {exc}"
                result_block["is_error"] = True
            else:
                result_block["content"] = outcome.content
                if outcome.is_error:
                    result_block["is_error"] = True
                if outcome.lookup is not None:
                    lookups.append(outcome.lookup)
            results.append(result_block)
        convo.append({"role": "user", "content": results})

    # Exhausted the rounds without a non-tool_use stop (the last call forced tool_choice
    # none, so this is only reached if the model still emitted tool_use). Return its text.
    return AgentCall(
        raw_text=_first_text(response),
        input_tokens=total_in,
        output_tokens=total_out,
        brain_lookups=lookups,
    )
