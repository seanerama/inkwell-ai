# Status & Handoff

> Runtime/ops truth (framework-spec §4.6). Generated from `.verity/runtime.json`
> by the Release/Deploy Operator. Secret LOCATIONS only — never values.

**Live version:** 0.0.12
**Deployed at:** 2026-09-16T19:29:06Z
**Rollback from:** ghcr.io/seanerama/inkwell-ai-server@sha256:60747bf2a40cda2b17b38f828271efeb22f1b9042564093bd9ed72ad90f284f4 (v0.0.11)

## Environments
- **staging:** {"status":"deployed v0.0.12 by digest (v0.3: Library + Formalize); all three release lanes green; deploy canary passed live; smoke gate passed 3/3","url":"https://mini-hp01.taile0ffc4.ts.net:8444","image":"ghcr.io/seanerama/inkwell-ai-server@sha256:373263b3fd1b393059760d3ae7aae5a2e2bf233c1061cf26aea85009965a83d1","deployed_at":"2026-09-16T22:48:42Z"}
- **prod:** {"status":"not deployed"}

## Secret locations (names + on-disk locations only, never values)
- ANTHROPIC_API_KEY, POSTGRES_PASSWORD, INKWELL_TOKEN_PEPPER, INKWELL_BLOB_SIGNING_KEY @ mini-hp01:/srv/inkwell/<env>/.env (mode 600; canonical copies in the operator's password manager)
- ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, ANDROID_KEY_PASSWORD @ GitHub Actions secrets on seanerama/inkwell-ai; offline backup ~/.verity/secrets/inkwell-ai/ on the operator workstation

## Coordination notes
- 2026-09-15: walking skeleton verified on the physical tablet against staging v0.0.4 (pair, Check, Ping round-trip). Pending on operator: rotate ANTHROPIC_API_KEY (exposed in a session log), sudo systemctl enable inkwell-staging. Prod not yet promoted (confirm gate).
- 2026-09-16: Tailscale SSH check mode expires roughly daily; the operator must re-approve the login link before deploy.sh can run.
- 2026-09-16: first successful real agent job on staging (canvas.ask replay of the owner's 'what is 1+9=?' export): 3038 in / 161 out tokens.
- v0.0.12 (stages 11 Library + 12 Formalize) deployed to staging 2026-09-16 by digest; health 0.0.12, canary canvas.ask done (1 annotation, 1 card), Playwright smoke 3/3 incl. new GET /canvases bearer gate (200 [] with tablet token). Prod NOT promoted (confirm gate). On-device smokes pending: smoke/library.md, smoke/formalize.md on the v0.0.12 APK. No new server env vars; client flags LIBRARY and FORMALIZE ON in release by documented exception.
