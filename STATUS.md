# Status & Handoff

> Runtime/ops truth (framework-spec §4.6). Generated from `.verity/runtime.json`
> by the Release/Deploy Operator. Secret LOCATIONS only — never values.

**Live version:** (none)
**Deployed at:** (not deployed)
**Rollback from:** (n/a)

## Environments
- **staging:** {"status":"not deployed — blocked on one-time host setup (deploy/host-setup.sh, needs sudo); release v0.0.3 ready","url":"https://mini-hp01.taile0ffc4.ts.net:8444","image":"ghcr.io/seanerama/inkwell-ai-server@sha256:b2831b45f8b97ac47a546aabb15506b3baf6ac2667c168d6950165652cd72b84"}
- **prod:** {"status":"not deployed"}

## Secret locations (names + on-disk locations only, never values)
- ANTHROPIC_API_KEY, POSTGRES_PASSWORD, INKWELL_TOKEN_PEPPER, INKWELL_BLOB_SIGNING_KEY @ mini-hp01:/srv/inkwell/<env>/.env (mode 600; canonical copies in the operator's password manager)
- ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD @ GitHub Actions secrets on seanerama/inkwell-ai; offline backup ~/.verity/secrets/inkwell-ai/ on the operator workstation

## Coordination notes
- (none)
