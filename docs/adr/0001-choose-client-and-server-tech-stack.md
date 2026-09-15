# 0001. Choose client and server tech stack

- **Status:** Accepted
- **Date:** 2026-09-15

## Context

Inkwell is a stylus-first handwriting workspace for one tablet (Lenovo Idea Tab Pro,
Android 14) talking to a server that runs vision-model jobs. SPEC.md §1.1 makes ink
quality the product: pressure, tilt, batched `MotionEvent` history samples, palm
rejection, and a one-euro filter, all rendered with no visible lag. The server side
is small: a job API, a worker that calls the Anthropic API, Postgres, and a blob store.

## Decision

- **Client:** native Android, Kotlin, Jetpack Compose for chrome, a custom `View`
  handling raw `MotionEvent` for the ink surface, Room for persistence,
  Retrofit + OkHttp + kotlinx.serialization for the network layer, WorkManager for
  sync polling. `minSdk` 31, `targetSdk` 34. Gradle with a committed version catalog
  and `gradle.lockfile` (dependency locking on).
- **Server:** Python 3.12, FastAPI, SQLAlchemy 2 + Alembic, Pydantic v2, Postgres 16,
  the official `anthropic` Python SDK. `uv` manages the environment with a committed
  `uv.lock`. Ruff for lint/format, pytest for tests.
- **Model default:** `claude-sonnet-5` per SPEC §4.1, overridable per space via the
  `model` field (Opus 5 is the expected upgrade for `canvas.formalize`).

## Alternatives considered

The design guide (`stack-and-topology`) leans to **server-rendered web with progressive
enhancement** before any rich client. That would mean a browser canvas on the tablet.
Rejected: browser pointer events on Android expose coalesced samples inconsistently,
Chrome adds input latency the spec's acceptance test would fail, and palm rejection
and tilt are unreliable. The ink surface is the product, so the client must be native.

**Expo / React Native** (the catalog's existing mobile pipeline) was considered because
a build pipeline already exists. Rejected: the ink surface would still need a native
module for `MotionEvent` history and the overlay renderer, so the JS layer would add
weight without removing the hard part.

**Server in Kotlin (Ktor)** to share one language. Rejected: the `anthropic` Python SDK
plus Pydantic gives structured outputs and schema validation with the least code, and
SPEC §14 already places the schema source of truth in `server/app/schemas.py`.

**Redis-backed queue, S3 from day one:** see ADR-0003 and ADR-0004.

## Consequences

- Two toolchains (Gradle/JVM and Python) in CI. Android builds are the slow lane
  (several minutes with Gradle caching); the CI matrix must cache both.
- The client cannot be smoke-tested with a browser-driven UI smoke; the Operator's
  post-deploy smoke covers the server API, and an instrumented test on an emulator
  covers the client (see the walking-skeleton definition).
- Dependency pinning: `uv.lock` and Gradle lockfiles are committed from Stage 0.
