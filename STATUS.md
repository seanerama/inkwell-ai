# Status & Handoff

> Runtime/ops truth (framework-spec §4.6). Generated from `.verity/runtime.json`
> by the Release/Deploy Operator. Secret LOCATIONS only — never values.

**Live version:** 0.0.11
**Deployed at:** 2026-09-16T19:29:06Z
**Rollback from:** ghcr.io/seanerama/inkwell-ai-server@sha256:5ac6373043492dd8f6eb71ae4b2c63c67127aa394143f6799da0a51a67edbf49 (v0.0.10)

## Environments
- **staging:** {"status":"deployed v0.0.11 by digest (Phase 2 complete: canary, full vocabulary, cards); all three release lanes green; deploy canary passed live; smoke gate passed","url":"https://mini-hp01.taile0ffc4.ts.net:8444","image":"ghcr.io/seanerama/inkwell-ai-server@sha256:60747bf2a40cda2b17b38f828271efeb22f1b9042564093bd9ed72ad90f284f4","deployed_at":"2026-09-16T19:29:06Z"}
- **prod:** {"status":"not deployed"}

## Secret locations (names + on-disk locations only, never values)
- ANTHROPIC_API_KEY, POSTGRES_PASSWORD, INKWELL_TOKEN_PEPPER, INKWELL_BLOB_SIGNING_KEY @ mini-hp01:/srv/inkwell/<env>/.env (mode 600; canonical copies in the operator's password manager)
- ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD @ GitHub Actions secrets on seanerama/inkwell-ai; offline backup ~/.verity/secrets/inkwell-ai/ on the operator workstation

## Coordination notes
- 2026-09-15: walking skeleton verified on the physical tablet against staging v0.0.4 (pair, Check, Ping round-trip). Pending on operator: rotate ANTHROPIC_API_KEY (exposed in a session log), sudo systemctl enable inkwell-staging. Prod not yet promoted (confirm gate).
- 2026-09-16: Tailscale SSH check mode expires roughly daily; the operator must re-approve the login link before deploy.sh can run.
- 2026-09-16: first successful real agent job on staging (canvas.ask replay of the owner's 'what is 1+9=?' export): 3038 in / 161 out tokens.
