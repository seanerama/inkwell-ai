# 0006. Obtain agent output with structured outputs against the frozen schema

- **Status:** Accepted
- **Date:** 2026-09-15

## Context

SPEC §6 requires the agent to return one JSON object matching a fixed schema, and
§10.4 describes fence-stripping, Pydantic validation, and one retry with the error
appended. The Anthropic Messages API now offers structured outputs
(`output_config.format`, with `client.messages.parse()` validating against a schema),
and assistant prefill is no longer available on current models, so the fence-strip
approach is both weaker and partly obsolete.

## Decision

- The agent call uses **structured outputs** with the JSON Schema from contract
  `agent-output` (v1) as the format, via the `anthropic` SDK's parse helper, so the
  response is schema-valid by construction. Fence stripping remains as a defensive
  fallback only if the parse helper is unavailable for the selected model.
- Server-side **semantic validation** still runs after parsing (Pydantic models in
  `server/app/schemas.py`): coordinate range checks per contract `coordinate-mapping`,
  annotation `id` uniqueness, card anchors referencing known annotation ids.
- On semantic failure: retry once with the validation error appended as a user turn.
  On second failure: job `failed`, one `error` card, never silent (SPEC §10.4).
- Adaptive thinking is left on (the API default on Sonnet 5 and Opus 5) with
  `output_config.effort` set per job type: `low` for `canvas.ask` and `canvas.extract`,
  `medium` for `canvas.annotate`, `high` for `canvas.formalize`.
- `max_tokens` 16000 non-streaming; the response is small JSON and the call is inside
  a worker, so streaming is not required (SPEC §1.2 non-goal).
- Token usage from `response.usage` is recorded on the job row for the `/usage`
  endpoint (SPEC §10.5).
- Brain context is placed in the system prompt inside an explicit delimiter and
  described as data, not instructions (SPEC §10.3). The static preamble and space
  prompt come first so prompt caching applies; volatile content goes last.

## Alternatives considered

- **Prompt-only JSON with fence stripping (SPEC §10.4 as written).** Works but
  depends on the model obeying formatting; structured outputs remove the failure
  class rather than retrying past it.
- **A single `tool_use` call with the schema as tool input.** Equivalent guarantee on
  older models, but forced `tool_choice` is being removed on the newest models, and
  structured outputs is the endorsed replacement.
- **Streaming.** Not needed for a job worker with small outputs; adds complexity to
  the retry path.

## Consequences

- The JSON Schema in `contracts/` must be valid for structured outputs
  (`additionalProperties: false`, required lists). The contract is written that way.
- Exact SDK calls are taken from the SDK documentation at build time, not recalled;
  the builder loads the `claude-api` skill and reads the Python README before writing
  `agent.py`.
