# Changelog

## 0.0.14

### Other
- Stage 18: key-rotation runbook + dual-pepper grace window and rotation tooling (#41)
- [stage 17] Backups: nightly pg_dump + blobs snapshot on the host, retention, off-host copy, tested restore (#40)
- Plan v0.5 ops hygiene: stage 17 (backups + tested restore), stage 18 (key-rotation runbook, dual-pepper grace); ADR-0011
- smoke: spaces + space-settings results on v0.0.13 (owner pass on tablet; Phase 3 acceptance met)
- Release v0.0.13: STATUS staging (Phase 3 spaces shipped; instrumented lane red → bug stage 16)

## 0.0.13

### Other
- Stage 15: Space settings on device — rename, colour, prompt, add a space (#36)
- [stage 14] Space tabs: server-mirrored spaces, per-space send and accent, move canvas between spaces (#35)
- Stage 13: Spaces on the server — default per-space prompts, create and edit (#34)
- Plan v0.4 Phase 3: stages 13 (server spaces), 14 (space tabs + mirror), 15 (space settings); ADR-0010
- smoke: library + formalize results on v0.0.12 (owner pass on tablet)
- Release v0.0.12: STATUS staging (v0.3 Library + Formalize shipped, smoke 3/3)

## 0.0.12

### Other
- Stage 12: Formalize — redraw a sketch as a clean diagram on a new canvas (#30)
- Stage 11: Library — many canvases, folders, titles; open, create, move, delete (#29)
- Plan v0.3: stage 11 (library: folders, many canvases) and stage 12 (formalize)
- smoke: diagram result on v0.0.11 (owner pass, formalize gap noted)
- Release v0.0.11: STATUS staging (Phase 2 shipped, clean lanes)

## 0.0.11

### Other
- androidTest: the fresh-open test asserts Room v2 (stage 10 bumped it; migration covered separately)
- Fix androidTest compile on the v0.0.10 tag; gate androidTest compilation on every PR
- Release v0.0.10: STATUS staging

## 0.0.10

### Other
- Stage 10: cards with actions, anchors and Markdown; job-type picker (#26)
- Stage 9: full annotation vocabulary — diagrams marked up legibly (#25)
- Stage 8: deploy canary — a live agent job proves every deploy (#24)
- Plan Phase 2: stages 8 (deploy canary), 9 (full vocabulary), 10 (cards with actions)
- smoke: ask results (stage 7 accepted on device, v0.0.9)
- Release v0.0.9: STATUS staging (first successful live agent job)

## 0.0.9

### Other
- Agent: prompt-guided JSON by default; structured outputs behind a flag (ADR-0006 amendment)
- Release v0.0.8: STATUS staging (stage 7 live)

## 0.0.8

### Other
- [stage 7] One-tap send: canvas.ask answers the note without an instruction (#20)
- Plan stage 7: one-tap send, canvas.ask answers the note (#17 -> #19)
- Release v0.0.7: STATUS staging

## 0.0.7

### Other
- Fix import order in contracts/check.py (ruff I001) after the INKWELL_CONTRACTS_DIR change
- Server image: ship the frozen contracts; gate proves the image can load them
- Release v0.0.6: clean release lanes; STATUS staging on v0.0.6

## 0.0.6

### Other
- Stage 6 verification prep: AGENT_ENABLED through compose; debug cleartext for the emulator test; on-demand instrumented workflow

## 0.0.5

### Other
- Ship v0.0.5: SEND_ENABLED on in release for staging verification; lock stage 6 deps
- Stage 6: the loop — canvas.annotate end to end with highlight (Phase 1) (#16)
- smoke: pairing results for v0.0.3/v0.0.4 on the tablet; Ping flag note updated
- STATUS: staging on v0.0.4
- CHANGELOG 0.0.4

## 0.0.4

### Other
- Enable Ping in release builds; derive the server version from the release tag (#15)
- STATUS: staging deployed v0.0.3
- deploy: run the host-side steps from a script file, not bash -s over stdin
- deploy.sh: start Postgres and wait for it before migrating
- Release v0.0.3: changelog and runtime truth (staging blocked on host setup, #14)

## 0.0.3

### Other
- Release: run Trivy from its pinned image instead of trivy-action

## 0.0.2

### Other
- Server image: pass the release Trivy gate

## 0.0.1

### Other
- Release fixes: valid trivy-action tag, drop unsupported setup-gradle input, commit CHANGELOG
- Ship prep: contract cleanup (#9), release hardening, sudo-free deploy, lockfiles (#13)
- Stage 5: agent runtime — prompt, structured output, validation, token accounting (#12)
- Stage 4: canvas export and coordinate mapping (device half of SPEC §5) (#11)
- Stage 3: ink capture and rendering (Phase 0) (#10)
- [stage 2] Android walking skeleton: pairing screen, ping round-trip, signed APK release (#8)
- Stage 1: server walking skeleton — API, Postgres queue, image, deploy (#7)
- Architect + Planner: ADRs, frozen contracts, walking skeleton, initial backlog
- Initial commit — scaffolded by Verity
