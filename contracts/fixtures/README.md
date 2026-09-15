# Contract fixtures

Golden examples both the server (pytest) and the client (Android JVM unit tests) must
agree on (ADR-0007). `valid-*.json` must be accepted; `invalid-*.json` must be rejected.

Validation has two tiers, and the fixtures cover both:

| Fixture | Fails at |
|---|---|
| `invalid-missing-summary` | JSON Schema (required) |
| `invalid-unknown-type` | JSON Schema (type vocabulary) |
| `invalid-pixel-coordinates` | semantic: coordinate range (contract `coordinate-mapping`) |
| `invalid-duplicate-id` | semantic: annotation ids unique within a response |
| `invalid-dangling-anchor` | semantic: `anchor.annotation_id` must exist in the response |

The `_reject_reason` key on invalid fixtures is documentation for humans. Harnesses
strip it before validation (it would otherwise trip `additionalProperties: false`
and mask the real reason).
