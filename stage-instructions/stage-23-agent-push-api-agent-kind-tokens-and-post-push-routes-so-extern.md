# Stage 23: Agent push API: agent-kind tokens and POST /push routes so external scripts can push documents

- **Type:** feature
- **Depends on:** 22
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/49
- **Design:** SPEC §1.1 ("agents can initiate"), §11 (security), §12 Phase 4 acceptance; contract `device-api` (additive routes under a dated section); ADR-0008, ADR-0012 §4

## Objectives

Stage 21's push works only from a shell on the host. After this stage any script
with an **agent-kind** token can push over HTTPS from anywhere on the tailnet —
including, later, the Nightshift bridge (#18). Device tokens cannot push; agent tokens
cannot read the device's jobs. From the workstation, one `curl` puts a PDF on the
tablet.

## What to build

**Token kinds (`db/models.py`, Alembic, `security/tokens.py`, `api/deps.py`, CLI)**
- `tokens.kind VARCHAR(16) NOT NULL DEFAULT 'device'` (additive migration).
- `inkwell token create --name <n> [--kind device|agent]`; `token list` shows the
  kind. Pepper/grace-window behaviour is identical for both kinds.
- Dependencies: `require_token` (device routes) rejects agent-kind tokens with `403
  forbidden`; new `require_agent_token` accepts only agent-kind. `GET /health` stays
  unauthenticated.

**Push routes (`app/api/routes/push.py`)** — additive, documented under "Stage 23
additions — <date>" in `contracts/device-api.md`:
- `POST /v1/push/document` multipart: `file` (same limits and sniffing as `POST
  /blobs`), `space` (slug), `title?`, `note?` → `201 { job_id, canvas_ids[] }`.
- `POST /v1/push/canvas` JSON `{ space, title, landscape? , note? }` → `201 { job_id,
  canvas_ids[] }`.
- Both call the stage 21 service; both honour `PUSH_ENABLED` (`403 disabled`);
  unknown space `404 not_found`; rate limit 10/min per agent token.
- `inkwell push` keeps working on the host unchanged.

**Docs** — `deploy/README.md` gets "Pushing from a script": mint an agent token,
the two curl examples, and the note that agent tokens are recorded in
`.verity/deploy-access.md` by location only.

## Interface contracts

- **Exposes:** `tokens.kind`, `POST /v1/push/document`, `POST /v1/push/canvas`,
  `require_agent_token`.
- **Consumes:** stage 21 push service and blob limits; ADR-0008 token hashing;
  ADR-0012.

## Testing requirements

- Auth matrix: device token on push routes → 403; agent token on `/jobs`, `/sync`,
  `/spaces`, `/canvases` → 403; agent token on push → 201; revoked agent token → 401;
  no token → 401.
- Push routes: 1-page and 3-page PDF, PNG, oversize, wrong mime, unknown space,
  kill-switch off, rate limit.
- Migration: default `device` for existing rows.
- Smoke `smoke/push-api.md`: from the workstation, mint an agent token on the host,
  `curl -F file=@brief.pdf -F space=work https://…:8444/v1/push/document`, watch the
  Work tab badge on the tablet. Results table.

## Acceptance conditions

- [ ] Kill-switch: same `PUSH_ENABLED` gate; agent tokens are a separate kind that cannot read device routes (tested)
- [ ] UI-smoke asset `smoke/push-api.md` authored
- [ ] Additive migration only (`tokens.kind` with default)
- [ ] Existing suite stays green; CI all-green

## Pipeline test: NO
