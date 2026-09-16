# Stage 13: Spaces on the server: default per-space prompts, create and edit spaces

- **Type:** feature
- **Depends on:** 12
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/31
- **Design:** SPEC §1.1 ("adding a new agent = adding a space"), §4.1, §10.3, §12 Phase 3; contract `device-api` (frozen routes `POST /spaces`, `PATCH /spaces/{id}`); ADR-0010

## Objectives

Today the four seeded spaces are identical agents: every `system_prompt` is empty, so
Work and Learning answer the same way. After this stage each default space carries a
distinct prompt (backfilled on deploy without touching any prompt a user has edited),
and spaces can be created and edited over the two routes the contract already
promises. This is the server half of the Phase 3 acceptance ("the same canvas sent
from Work and from Learning produces recognizably different responses"); stage 14
proves it from the tablet.

## What to build

**Seed (`app/db/seed.py`, `inkwell db seed`)**
- `DEFAULT_SPACES` gains a `system_prompt` per slug. Write them as short, concrete
  agent briefs (60–120 words each, second person, no JSON/format talk — the preamble
  owns format). Intent:
  - `work`: a sharp colleague. Surface decisions, owners, deadlines and next actions;
    prefer `task` cards; be terse.
  - `home`: a practical household helper. Errands, lists, plans, family logistics;
    warm tone; prefer `answer` cards with checklists.
  - `learning`: a patient tutor. Explain the concept behind what was written, check
    understanding with one question, suggest the next thing to try; prefer
    `explanation` cards; never just give the answer to an exercise without the why.
  - `business`: a strategy and finance lens. Numbers, assumptions, risks, the
    questions that must be answered before a decision; prefer `answer` and `task`
    cards; flag missing numbers explicitly.
- `seed_default_spaces` stays idempotent and additionally **backfills** `system_prompt`
  for an existing default row only when its stored prompt is empty. A user-edited
  prompt is never overwritten. `deploy/remote-deploy.sh` already runs the seeder on
  every deploy, so staging picks the prompts up on the next release with no operator
  step.

**Routes (`app/api/routes/spaces.py`, `app/api/schemas.py`)**
- `POST /spaces` → `201` + `SpaceOut`. Body `SpaceCreate { name (1–120), slug?
  (^[a-z0-9-]{1,64}$; derived from name when absent), system_prompt? (≤ 8000 chars),
  tools? (list of ids), model? (default `claude-sonnet-5`), color? (`#RRGGBB`,
  default `#000000`), position? (default: max+1) }`. Duplicate slug → `409 conflict`.
- `PATCH /spaces/{id}` → `200` + `SpaceOut`. Body `SpaceUpdate` with every field
  optional: `name, system_prompt, tools, model, color, position`. `slug` present →
  `422 validation` ("slug is immutable"). Unknown id → `404 not_found`. Same
  validation limits as create. Partial semantics: absent fields untouched
  (`exclude_unset`).
- Both require the bearer token. Both are gated by a **kill-switch**
  `SPACES_EDITABLE` (`Settings.spaces_editable`, default `False`, aliases
  `SPACES_EDITABLE` / `INKWELL_SPACES_EDITABLE`, passed through `deploy/compose.yml`
  like `AGENT_ENABLED`): when off they return `403` with `error.code = "disabled"`.
  `GET /spaces` is unaffected.
- Document the request bodies and error codes under a dated additive section in
  `contracts/device-api.md` ("Stage 13 additions — 2026-09-16"). The route table
  itself is untouched.

**Prompt path**
- No change to `build_system_prompt`; the worker already passes `space.system_prompt`
  (stage 5). Add a test that proves a job in `learning` sends Learning's prompt and a
  job in `work` sends Work's, via the fake client's recorded `system`.

**Operator**
- `inkwell canary --space <slug>` already exists; no change. `deploy/.env.example`
  gains `SPACES_EDITABLE=false` with a comment.

## Interface contracts

- **Exposes:** `POST /spaces`, `PATCH /spaces/{id}` (bodies above); non-empty default
  prompts on the four seeded spaces; `SPACES_EDITABLE` env.
- **Consumes:** `device-api` (frozen route table; additive section only),
  `agent-output` (unchanged), ADR-0010.

## Testing requirements

- Seed: fresh DB → four rows with non-empty distinct prompts; second run creates 0
  and changes nothing; a row whose prompt was edited keeps the edit; a row with an
  empty prompt is backfilled.
- Routes: 401 without token; 403 `disabled` when the switch is off (default);
  create happy path (201, slug derived, position appended); duplicate slug 409;
  bad colour / overlong prompt / bad slug 422; PATCH partial update leaves other
  fields; PATCH with `slug` 422; unknown id 404.
- Prompt: per-space `system` content as above; `tools` never reaches the agent call.
- Smoke asset `smoke/spaces-server.md`: curl steps against staging — `GET /spaces`
  shows four distinct prompts; `PATCH` Learning's colour and read it back; a
  `canvas.ask` submitted with `space_id` = Learning returns a tutor-toned answer
  (operator judges). Results table.

## Acceptance conditions

- [ ] Kill-switch `SPACES_EDITABLE` (default OFF) gates `POST`/`PATCH /spaces`
- [ ] UI-smoke asset `smoke/spaces-server.md` authored
- [ ] Additive only: no Alembic migration needed (all columns exist); contract table untouched, dated additive section added
- [ ] Seeder backfills empty prompts and never overwrites edited ones (tested)
- [ ] A `learning` job's system prompt differs from a `work` job's (tested)
- [ ] Existing suite stays green; CI all-green

## Pipeline test: NO
