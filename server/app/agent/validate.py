"""Output extraction, two-tier validation, and the one-retry loop (SPEC §10.4, ADR-0006).

Flow for a single agent run:

1. Call the model with structured outputs (``agent.client.create_message``).
2. Strip ```` ```json ```` / ```` ``` ```` fences defensively (SPEC §10.4) and parse JSON.
3. Validate: Pydantic schema (``app.schemas.AgentOutput``) then semantic checks
   (``app.contracts.validation`` — coordinate clamp/reject, unique ids, anchors resolve),
   counting ``coordinate_clamps``.
4. On failure, append the model's reply and the validation error (including the offending
   JSON path) as new turns and retry ONCE. On the second failure, raise
   ``AgentValidationError`` carrying the tokens spent, so the caller can record usage and
   fail the job with one error card.
"""

from __future__ import annotations

import json
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

from pydantic import ValidationError

from app.agent.api_schema import for_structured_output
from app.agent.client import AgentCall, create_message, image_block, text_block
from app.agent.prompt import build_instruction, build_system_prompt
from app.contracts.check import frozen_schema
from app.contracts.validation import SemanticError, validate_agent_output_counted
from app.logging import get_logger
from app.schemas import AgentOutput

log = get_logger("agent")

MAX_ATTEMPTS = 2


@dataclass
class AgentRun:
    output: AgentOutput
    input_tokens: int
    output_tokens: int
    coordinate_clamps: int
    # Stage 26: {"query", "count", "mode"} per brain_search across all attempts
    # (traceability; "mode" = "and" | "or" since Stage 30).
    brain_lookups: list[dict] = field(default_factory=list)


class AgentValidationError(Exception):
    """The model failed validation on every attempt (SPEC §10.4)."""

    def __init__(self, message: str, *, input_tokens: int, output_tokens: int) -> None:
        super().__init__(message)
        self.input_tokens = input_tokens
        self.output_tokens = output_tokens


def extract_json(raw_text: str) -> dict:
    """Strip Markdown fences (SPEC §10.4) and parse the JSON object."""
    text = raw_text.strip()
    if text.startswith("```"):
        # Drop the opening fence line (```json or ```), and the trailing fence.
        first_newline = text.find("\n")
        if first_newline != -1:
            text = text[first_newline + 1 :]
        if text.rstrip().endswith("```"):
            text = text.rstrip()[: -len("```")]
    text = text.strip()
    data = json.loads(text)
    if not isinstance(data, dict):
        raise ValueError("agent output is not a JSON object")
    return data


def run_agent(
    *,
    model: str,
    system_prompt: str,
    brain_context: str,
    image_b64: str,
    instruction: str | None,
    job_type: str,
    job_id: str,
    tools: list[dict] | None = None,
    tool_executor: Callable[[str, dict], Any] | None = None,
) -> AgentRun:
    """Run the agent for one job, with two-tier validation and one retry.

    ``tools``/``tool_executor`` (Stage 26) are threaded to ``create_message``; when tools
    are empty or ``AGENT_TOOLS_ENABLED`` is off the call stays byte-identical to today's.
    """
    # The frozen contract is the schema of record; the API gets its supported projection
    # (unsupported constraints are re-checked by Pydantic after the call).
    schema = for_structured_output(frozen_schema())
    system = build_system_prompt(system_prompt, brain_context, job_type=job_type)
    messages: list[dict] = [
        {
            "role": "user",
            "content": [
                image_block(image_b64),
                text_block(build_instruction(instruction, job_type)),
            ],
        }
    ]

    total_in = 0
    total_out = 0
    brain_lookups: list[dict] = []
    last_error: Exception | None = None

    for attempt in range(MAX_ATTEMPTS):
        call: AgentCall = create_message(
            model=model,
            system=system,
            messages=messages,
            schema=schema,
            job_type=job_type,
            tools=tools,
            tool_executor=tool_executor,
        )
        total_in += call.input_tokens
        total_out += call.output_tokens
        brain_lookups.extend(call.brain_lookups)

        try:
            data = extract_json(call.raw_text)
            output, clamps = validate_agent_output_counted(data)
        except (ValidationError, SemanticError, ValueError) as exc:
            last_error = exc
            path = getattr(exc, "path", None)
            log.warning(
                "agent.validation_failed",
                job_id=job_id,
                attempt=attempt + 1,
                error=str(exc).splitlines()[0],
                path=path,
            )
            if attempt + 1 >= MAX_ATTEMPTS:
                break
            # Append the model's reply and the error (with the offending path) so the
            # retry can correct it (SPEC §10.4 / ADR-0006).
            messages.append({"role": "assistant", "content": call.raw_text})
            messages.append(
                {
                    "role": "user",
                    "content": (
                        "That response failed validation and was rejected: "
                        f"{exc}. Return a corrected agent-output JSON object that fixes "
                        "this and nothing else."
                    ),
                }
            )
            continue

        if clamps:
            log.info("agent.coordinate_clamps", job_id=job_id, coordinate_clamps=clamps)
        return AgentRun(
            output=output,
            input_tokens=total_in,
            output_tokens=total_out,
            coordinate_clamps=clamps,
            brain_lookups=brain_lookups,
        )

    raise AgentValidationError(
        f"agent output failed validation after {MAX_ATTEMPTS} attempts: {last_error}",
        input_tokens=total_in,
        output_tokens=total_out,
    )
