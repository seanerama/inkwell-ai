# 0008. Authenticate devices with server-issued bearer tokens

- **Status:** Accepted
- **Date:** 2026-09-15

## Context

SPEC §11: a long-lived bearer token issued at pairing, stored in Android Keystore,
with `/jobs` rate-limited per token; the Anthropic key never leaves the server. There
is one user and, initially, one device. The server is on a private tailnet
(ADR-0005), so the token is a second factor on top of network membership.

## Decision

- A `device_tokens` table: `id`, `name`, `token_hash` (SHA-256 with a server pepper
  from `INKWELL_TOKEN_PEPPER`), `created_at`, `last_seen_at`, `revoked_at`.
- Tokens are minted by an operator CLI in the server image:
  `inkwell token create --name tablet` prints the plaintext once. Pairing is the user
  pasting or scanning that token into the app's pairing screen. Revocation is
  `inkwell token revoke <id>`.
- Every `/v1/*` route except `/v1/health` requires `Authorization: Bearer <token>`.
  Rate limit: 30 job submissions per token per minute, 429 with `Retry-After`.
- The app stores the token with `EncryptedSharedPreferences` backed by an
  Android Keystore master key. Never plain `SharedPreferences`.
- No user accounts, no OAuth, no refresh flow in v1. Multi-user is a new contract.

## Alternatives considered

- **Tailscale identity headers only** (`tailscale serve` can inject the caller's
  identity). Elegant, but it couples auth to the deployment method and breaks on the
  Coolify promotion path.
- **Pairing endpoint with a short-lived code** (`POST /pair`). Nicer UX; deferred as
  an additive feature because a CLI-minted token is enough for one device today.
- **JWT.** Nothing to encode and nothing to verify statelessly; a hashed opaque token
  is simpler and revocable.

## Consequences

- The token pepper is a server secret; rotating it invalidates all tokens (documented
  in the SRE runbook).
- The per-space daily job cap (SPEC §10.5) is counted per space, not per token, and is
  enforced in the worker claim step.
