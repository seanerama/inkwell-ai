# Changelog

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
