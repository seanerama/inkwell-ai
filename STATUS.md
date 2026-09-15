# Status & Handoff

> Runtime/ops truth (framework-spec §4.6). Generated from `.verity/runtime.json`
> by the Release/Deploy Operator. Secret LOCATIONS only — never values.

**Live version:** 0.0.4
**Deployed at:** 2026-09-15T22:47:20Z
**Rollback from:** ghcr.io/seanerama/inkwell-ai-server@sha256:b2831b45f8b97ac47a546aabb15506b3baf6ac2667c168d6950165652cd72b84 (v0.0.3; ./deploy/deploy.sh staging <that ref>)

## Environments
- **staging:** {"status":"deployed v0.0.4 by digest; api+worker+postgres up; smoke gate passed on v0.0.3, health re-verified on v0.0.4; systemd unit installed, enable pending (sudo)","url":"https://mini-hp01.taile0ffc4.ts.net:8444","image":"ghcr.io/seanerama/inkwell-ai-server@sha256:ea9b890f3b89403aa2c7f9e5c02b75d613ebaea1126210368a193b5a63cd6b1b","deployed_at":"2026-09-15T22:47:20Z"}
- **prod:** {"status":"not deployed"}

## Secret locations (names + on-disk locations only, never values)
- ANTHROPIC_API_KEY, POSTGRES_PASSWORD, INKWELL_TOKEN_PEPPER, INKWELL_BLOB_SIGNING_KEY @ mini-hp01:/srv/inkwell/<env>/.env (mode 600; canonical copies in the operator's password manager)
- ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD @ GitHub Actions secrets on seanerama/inkwell-ai; offline backup ~/.verity/secrets/inkwell-ai/ on the operator workstation

## Coordination notes
- 2026-09-15: walking skeleton verified on the physical tablet against staging v0.0.4 (pair, Check, Ping round-trip). Pending on operator: rotate ANTHROPIC_API_KEY (exposed in a session log), sudo systemctl enable inkwell-staging. Prod not yet promoted (confirm gate).
