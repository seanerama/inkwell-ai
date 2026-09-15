# Stage 5: Agent runtime: prompt, structured output, validation, token accounting

- **Type:** feature
- **Depends on:** 1
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/5
- **Design:** SPEC §10.2–10.5, §5.3, §6, ADR-0006, ADR-0007, contracts `agent-output`, `coordinate-mapping`

## Objectives

Server-only: turn a `canvas.annotate` job into a validated `agent-output` object
using the Anthropic API with structured outputs, with two-tier validation, one
retry, an error card on failure, and token accounting. Testable end to end with a
recorded model response; one real-API test runs only when a key is present.

## What to build

- `agent/prompt.py`: system prompt assembled in the fixed order (preamble with the
  verbatim coordinate sentence from contract `coordinate-mapping` and the output
  schema description; space `system_prompt`; delimited brain context marked as data,
  empty in this stage; job-type guidance). Static parts first for prompt caching.
- `agent/client.py`: the Anthropic call. **The builder loads the `claude-api` skill
  and reads its Python README before writing this file**; exact SDK usage is not
  recalled. Model from `space.model` (default `claude-sonnet-5`), structured outputs
  against the committed schema, adaptive thinking left at the API default,
  `output_config.effort` per job type (`annotate` → `medium`), `max_tokens` 16000,
  the image as a base64 PNG block followed by the instruction text block.
- `agent/validate.py`: schema parse (Pydantic from Stage 1) → semantic checks
  (coordinate clamp/reject per contract with the `coordinate_clamps` counter and
  the offending path in the log, unique ids, anchors resolve) → retry once with the
  error appended as a user turn → on second failure job `failed` plus one `error`
  card.
- `jobs/handlers/canvas_annotate.py` registered for `canvas.annotate`: loads the
  image from the blob store, enforces the per-space daily cap (default 200) at claim,
  runs the agent, writes `jobs.result` with `contract_version`, creates `cards` rows,
  records `input_tokens`/`output_tokens` on the job.
- `GET /v1/usage?since=` per contract `device-api`.
- Kill-switch: `AGENT_ENABLED` (default `false`); when off, `canvas.annotate` jobs
  fail immediately with an `error` card saying the agent is disabled.
- `ANTHROPIC_API_KEY` read only on the server; never logged; redaction test.

## Interface contracts

- **Exposes:** `canvas.annotate` on `POST /jobs` (no longer 422); `GET /usage`;
  `run_agent(job) -> AgentOutput` for later job types.
- **Consumes:** contracts `agent-output` (schema file and fixtures),
  `coordinate-mapping` (validation rules), `device-api` (job shapes); Stage 1 handler
  registry and `BlobStore`.

## Testing requirements

- Unit: prompt assembly order and the verbatim coordinate sentence; effort per job
  type; brain delimiter present and empty.
- Validation: each fixture behaves per `contracts/fixtures/README.md`; a response
  with `x = 1.03` is clamped to `1.0` and `coordinate_clamps == 1`; `x = 1.2` triggers
  the retry path; two failures → `failed` + `error` card; the retry message contains
  the offending JSON path.
- Integration with a **recorded** client (a fake `anthropic` client returning the
  `valid-full-vocabulary` fixture): a queued `canvas.annotate` job ends `done`, its
  result carries `contract_version`, cards rows exist, tokens are recorded, `/usage`
  sums them.
- Kill-switch off → immediate `failed` with the disabled error card.
- Redaction: the API key never appears in any log line even at debug level.
- `tests/live/test_real_call.py`: skipped unless `INKWELL_LIVE_TESTS=1` and a key are
  set; posts a generated three-box PNG and asserts a single valid highlight whose
  bbox centre is in the middle third. Not a CI gate; run by the Tester before Stage 6.

## Acceptance conditions

- [ ] Kill-switch / dark-launch flag (default OFF) for this net-new feature:
      `AGENT_ENABLED=false` by default.
- [ ] UI-smoke "observably-works" check authored for any user-facing surface:
      `smoke/server-usage.md` (`GET /usage` returns zeros on a fresh staging).
- [ ] Additive migration only (no destructive schema change): new nullable
      `input_tokens`, `output_tokens`, `coordinate_clamps` columns on `jobs`.
- [ ] Existing suite stays green; CI all-green

## Pipeline test: YES
