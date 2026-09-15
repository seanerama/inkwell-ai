# 0002. Choose a monorepo with a modular-monolith server and a native client

- **Status:** Accepted
- **Date:** 2026-09-15

## Context

The system has two deployable components: an Android APK and a server. SPEC §10.1
requires that jobs never run inside the request handler, which suggests a worker.
The guide warns that every extra service multiplies the CI matrix, image set, and
deploy surface, and extends the image slug per service.

## Decision

- **One repository** laid out as SPEC §14 describes: `android/`, `server/`,
  `contracts/`, `docs/`.
- **The server is a modular monolith in one image**, `ghcr.io/seanerama/inkwell-ai-server`.
  It has two entrypoints, `api` (uvicorn) and `worker` (the job loop), selected by
  the container command. Same code, same image, same version, two processes.
- Internal modules with enforced boundaries: `api/` (routers, auth), `jobs/` (queue,
  worker), `agent/` (prompt assembly, model call, output validation), `brain/`,
  `blobs/`, `db/`. Modules talk through Python interfaces, not through HTTP.
- **The client is not a container image.** Its artifact is a signed APK attached to a
  GitHub Release for the same tag as the server image.
- Postgres runs as a sidecar container in the compose stack, not as a separately
  deployed service of ours.

## Alternatives considered

The guide recommends a modular monolith; we follow it. Considered and rejected:

- **Separate `api` and `worker` images.** Doubles the image set for no independent
  scaling need (a single user, a 200 jobs/day cap). One image with two commands keeps
  version skew impossible.
- **Worker inside the API process as a background task.** Simpler still, but a long
  model call and a uvicorn restart would kill in-flight jobs, and SPEC §10.1 forbids it.
- **Two repositories.** Contracts would drift; SPEC §14 explicitly warns about two
  hand-maintained schema copies.

## Consequences

- One server image, one CI job for Python, one for Android, one release tag for both.
- Image slug: `ghcr.io/seanerama/inkwell-ai-server:<tag>`. Built multi-arch
  (`linux/amd64` for mini-hp01, `linux/arm64` so promotion to the ARM EC2 host later
  needs no rebuild).
- The worker and API share the database as their only coupling point (ADR-0003).
