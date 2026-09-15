# 0003. Run agent jobs through a Postgres-backed queue

- **Status:** Accepted
- **Date:** 2026-09-15

## Context

SPEC §1.3 invariant 4: all agent work is asynchronous and modeled as jobs. §10.1
allows arq, Celery, or a plain asyncio queue. Volume is tiny (per-space daily cap of
200) and there is one user. The `jobs` table already exists as the source of truth
for job state in both directions.

## Decision

The `jobs` table **is** the queue. The worker claims work with
`SELECT ... WHERE status='queued' ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1`,
sets `status='running'` with a `locked_at`/`locked_by`, runs the job, and writes the
result in a final transaction. A job left `running` past a lease timeout is requeued
once, then failed with an error card. The worker polls with a short sleep when idle
and uses `LISTEN/NOTIFY` on insert to wake immediately.

Cancel (`POST /jobs/{id}/cancel`) flips `queued → cancelled` directly; a `running` job
is marked `cancel_requested` and the worker checks the flag after the model call.

## Alternatives considered

- **arq or Celery with Redis.** The conventional choice, but it adds a Redis container,
  a second connection pool, and a second place job state can disagree with the table
  the API reads. Nothing here needs sub-second dispatch or fan-out.
- **In-process asyncio queue.** Rejected in ADR-0002: not durable across restarts.
- **Cloudflare Queues / managed queue.** The server is self-hosted on a tailnet
  (ADR-0005); a cloud queue would force a public egress path for no gain.

## Consequences

- Zero extra infrastructure. Job state has exactly one home.
- Throughput ceiling is one job per worker process per model-call duration. Fine for
  v1; the worker command accepts `--concurrency N` (asyncio tasks, each with its own
  claim) if that ever matters.
- Sync (`GET /sync?cursor=`) is a query on `jobs.updated_at` with a monotonic cursor
  (see contract `device-api`).
