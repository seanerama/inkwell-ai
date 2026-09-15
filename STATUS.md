# Status & Handoff

> Runtime/ops truth (framework-spec §4.6). Generated from `.verity/runtime.json`
> by the Release/Deploy Operator. Secret LOCATIONS only — never values.

**Live version:** 0.0.5
**Deployed at:** 2026-09-15T23:36:06Z
**Rollback from:** ghcr.io/seanerama/inkwell-ai-server@sha256:ea9b890f3b89403aa2c7f9e5c02b75d613ebaea1126210368a193b5a63cd6b1b (v0.0.4)

## Environments
- **staging:** {"status":"deployed v0.0.5 by digest; AGENT_ENABLED=true (stage 6 verification); smoke gate passed; systemd enabled. Release lane note: android-instrumented failed on v0.0.5 (test-only cleartext policy, fixed on main, verify via instrumented.yml before v0.0.6)","url":"https://mini-hp01.taile0ffc4.ts.net:8444","image":"ghcr.io/seanerama/inkwell-ai-server@sha256:30b2b2f65ecfc699eaf579a588394d61152b2c6c76eebc7f2a00e4dafeff62ac","deployed_at":"2026-09-15T23:36:06Z"}
- **prod:** {"status":"not deployed"}

## Secret locations (names + on-disk locations only, never values)
- ANTHROPIC_API_KEY, POSTGRES_PASSWORD, INKWELL_TOKEN_PEPPER, INKWELL_BLOB_SIGNING_KEY @ mini-hp01:/srv/inkwell/<env>/.env (mode 600; canonical copies in the operator's password manager)
- ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD @ GitHub Actions secrets on seanerama/inkwell-ai; offline backup ~/.verity/secrets/inkwell-ai/ on the operator workstation

## Coordination notes
- 2026-09-15: walking skeleton verified on the physical tablet against staging v0.0.4 (pair, Check, Ping round-trip). Pending on operator: rotate ANTHROPIC_API_KEY (exposed in a session log), sudo systemctl enable inkwell-staging. Prod not yet promoted (confirm gate).
