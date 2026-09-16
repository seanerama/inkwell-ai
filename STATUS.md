# Status & Handoff

> Runtime/ops truth (framework-spec §4.6). Generated from `.verity/runtime.json`
> by the Release/Deploy Operator. Secret LOCATIONS only — never values.

**Live version:** 0.0.7
**Deployed at:** 2026-09-16T00:47:51Z
**Rollback from:** ghcr.io/seanerama/inkwell-ai-server@sha256:4a3679129e8e5f89a2af8bc418eec8af8ad8d2a3361d8e9a1eb79c974b20b3b1 (v0.0.6)

## Environments
- **staging:** {"status":"deployed v0.0.7 by digest (contracts shipped inside the image; canvas.* jobs unblocked); AGENT_ENABLED=true; smoke gate passed; systemd enabled","url":"https://mini-hp01.taile0ffc4.ts.net:8444","image":"ghcr.io/seanerama/inkwell-ai-server@sha256:d2e24cae41055d485b35cda25a7d962eaf9c665be04a2073f4d8d3815ab38873","deployed_at":"2026-09-16T00:47:51Z"}
- **prod:** {"status":"not deployed"}

## Secret locations (names + on-disk locations only, never values)
- ANTHROPIC_API_KEY, POSTGRES_PASSWORD, INKWELL_TOKEN_PEPPER, INKWELL_BLOB_SIGNING_KEY @ mini-hp01:/srv/inkwell/<env>/.env (mode 600; canonical copies in the operator's password manager)
- ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD @ GitHub Actions secrets on seanerama/inkwell-ai; offline backup ~/.verity/secrets/inkwell-ai/ on the operator workstation

## Coordination notes
- 2026-09-15: walking skeleton verified on the physical tablet against staging v0.0.4 (pair, Check, Ping round-trip). Pending on operator: rotate ANTHROPIC_API_KEY (exposed in a session log), sudo systemctl enable inkwell-staging. Prod not yet promoted (confirm gate).
