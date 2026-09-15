# Inkwell AI — Architecture overview

Produced by the Architect role on 2026-09-15 from the locked identity
(`inkwell-ai`, images `ghcr.io/seanerama/inkwell-ai`) and SPEC.md. This is the handoff
to `/verity:plan`.

## Shape

```
Lenovo tablet (Kotlin/Compose, Room)  ──Tailscale HTTPS + Bearer──▶  mini-hp01
   ink/ render/ data/ net/ ui/                                       ┌────────────────────────┐
   exports PNG (1568 px longest edge)                                │ inkwell-ai-server image │
   renders agent-output geometry                                     │  api  ── Postgres 16 ── worker ──▶ Anthropic API
                                                                     │  blobs volume (LocalBlobStore)
                                                                     └────────────────────────┘
```

- **Topology:** monorepo; one server image with `api` and `worker` commands; client is
  a signed APK. See ADR-0002.
- **Queue:** the `jobs` table with `SKIP LOCKED`. ADR-0003.
- **Blobs:** local volume behind `BlobStore`; S3 later by config. ADR-0004.
- **Deploy:** staging (`:8444`) and prod (`:8443`) compose stacks on mini-hp01 behind
  `tailscale serve` at `mini-hp01.taile0ffc4.ts.net`;
  APK via GitHub Releases. ADR-0005. Access locations in `.verity/deploy-access.md`
  (gitignored; ask the admin).
- **Model:** structured outputs against the frozen `agent-output` schema; Sonnet 5 by
  default, per-space override. ADR-0006.
- **Schema:** committed JSON Schema is the single source; Pydantic and generated
  Kotlin are both checked against it and against shared fixtures. ADR-0007.
- **Auth:** CLI-minted, hashed device bearer tokens. ADR-0008.

## Frozen contracts (`contracts/`)

| Contract | Seam |
|---|---|
| `agent-output` | model → server → device: the JSON the agent returns |
| `device-api` | device ↔ server HTTP API, auth, errors, sync cursor |
| `coordinate-mapping` | exporter → prompt → validator → renderer geometry rules |
| `ink-storage` | on-device Room format for strokes |

## Accepted drop-in features

None for the initial backlog. `helper-bot` deferred to after Phase 3 (ADR-0009);
its structured-logging prerequisite is folded into Stage 0.

## Suggested stage order for the planner

Stage 0 is defined in `walking-skeleton.md`. After it, SPEC §12's phases map to thin
stages roughly as:

1. **Ink** (SPEC Phase 0): capture, one-euro filter, overlay renderer, eraser,
   pan/zoom, Room persistence. Acceptance is the feel test; nothing else proceeds
   until it passes.
2. **Export + coordinate mapping** on device, with the NM→CU fixture tests.
3. **Agent runtime**: prompt assembly, structured-output call, two-tier validation,
   retry, error card, token accounting. Server-only, testable with a recorded
   model response.
4. **The loop** (SPEC Phase 1): `canvas.annotate` end to end with `highlight` only.
5. **Full vocabulary + cards + layer tray** (Phase 2).
6. **Spaces UI** (Phase 3).
7. **Bidirectional `to_user` jobs + rasters** (Phase 4).
8. **Brain** (Phase 5).
9. **Ops:** backups for `pgdata` + `blobs`, token pepper rotation runbook, promotion
   to Coolify with `S3BlobStore` (when needed).

SPEC §13 open questions stay open; the planner should schedule "canvas size" and
"agent ink style" for review after stage 4.
