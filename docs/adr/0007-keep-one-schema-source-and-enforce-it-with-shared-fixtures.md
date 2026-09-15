# 0007. Keep one schema source and enforce it with shared fixtures

- **Status:** Accepted
- **Date:** 2026-09-15

## Context

SPEC §14: "Keep §6's schema definitions in exactly one place and generate the Kotlin
data classes from it. Two hand-maintained copies will diverge, and the divergence will
present as annotations silently failing to render." Two languages consume the same
shape: Pydantic on the server (validation and structured output), kotlinx on the
device (rendering).

## Decision

- **The committed JSON Schema is the source of truth:**
  `contracts/schema/agent-output.v1.schema.json` and
  `contracts/schema/device-api.v1.schema.json`. Frozen per the contracts-first guide;
  additive changes only.
- **Server conformance:** `server/app/schemas.py` Pydantic models must emit a JSON
  Schema equal to the committed file (a pytest compares them after normalisation).
  Editing the Pydantic model without editing the contract fails CI.
- **Client conformance:** Kotlin data classes are **generated** from the JSON Schema by
  a Gradle task using `quicktype` (kotlinx.serialization target) into
  `android/app/build/generated/contracts/`; they are never hand-edited. If generation
  proves unworkable on a construct, the fallback is committed hand-written classes
  plus the fixture test below, which still catches drift.
- **Golden fixtures:** `contracts/fixtures/agent-output/*.json` (valid and invalid
  cases). Both the pytest suite and the Android JVM unit tests must accept every
  `valid-*.json` and reject every `invalid-*.json`. This is the language-neutral
  drift check and is a CI gate from the stage that introduces rendering.

## Alternatives considered

- **Pydantic as the source, schema emitted at build time.** The spec's literal
  suggestion. Rejected only in form: emitting from Pydantic makes the server the
  owner of a contract the device depends on equally, and a contract file that CI
  regenerates is not frozen. Committing the schema and testing Pydantic against it
  gives the same single source with a real freeze.
- **Protobuf / gRPC.** Strong typing across both languages, but the payload is JSON
  to and from a model that speaks JSON Schema natively, and it adds a toolchain.
- **Hand-written classes on both sides with review discipline.** The exact failure
  the spec warns about.

## Consequences

- Adding an annotation type is: edit the schema (additive), add fixtures, regenerate
  Kotlin, extend Pydantic, extend the renderer. Any step skipped fails a gate.
- `quicktype` runs via `npx` pinned in `android/package.json`; Node is a build-time
  dependency of the Android module only.
