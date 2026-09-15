# Stage 2: Android walking skeleton: pairing screen, ping round-trip, signed APK release

- **Type:** feature
- **Depends on:** 1
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/2
- **Design:** `docs/architecture/walking-skeleton.md` (client half), ADR-0001, 0005, 0007, 0008

## Objectives

Prove the spine from the tablet: a signed APK on the tailnet pairs with a CLI-minted
token, reads `/health`, submits a `system.ping` job, and watches it come back `done`
through `/sync`. Also freezes the Room schema at version 1 and wires contract class
generation so later stages inherit both.

## What to build

- `android/` Gradle 8 project, version catalog, dependency locking, `minSdk` 31,
  `targetSdk` 34, package `com.inkwell`. Modules per SPEC §14 (`ink/`, `render/`,
  `data/`, `net/`, `ui/`) as packages; `ink/` and `render/` empty placeholders.
- `net/`: Retrofit + OkHttp + kotlinx.serialization client for the Stage 1 routes,
  `X-Inkwell-Contract` header, error envelope parsing, an `AuthInterceptor` reading
  the token from `EncryptedSharedPreferences` (Keystore master key).
- `data/`: Room database version 1 with the contract `ink-storage` entities and DAOs;
  `room.schemaLocation` set and `android/app/schemas/` committed. Packed-points
  codec (`FloatArray` stride 5 ↔ BLOB) with the `point_count * 20 == blob length`
  invariant.
- Contract class generation: Gradle task running pinned `quicktype` (via
  `android/package.json`) from `contracts/schema/agent-output.v1.schema.json` into
  `build/generated/contracts/`; a `contracts/` source set consumes it. If quicktype
  cannot express the `anyOf` union with kotlinx.serialization, fall back to committed
  hand-written classes plus the fixture tests (ADR-0007 fallback) and note it in the PR.
- `ui/`: one Compose `PairingScreen` (server URL, token, Check, Ping, status line).
  Ping posts `system.ping`, then polls `/sync` on a 5 s cadence until `done`/`failed`.
- `.verity/gates.json`: add `android-lint`, `android-test`, `android-build`.
  `ci.yml`: JDK 17, `gradle/actions/setup-gradle` caching. `release.yml`: on `v*`,
  decode the keystore from secrets, `assembleRelease`, attach the APK to the Release
  (catalog method `gradle-github-releases`).

## Interface contracts

- **Exposes:** the `net/` API client, the Room database and DAOs (contract
  `ink-storage`), generated `AgentOutput` Kotlin types (contract `agent-output`),
  the sync poller.
- **Consumes:** contract `device-api` v1 from Stage 1; contract `ink-storage`.

## Testing requirements

- JVM unit tests: generated (or fallback) contract classes accept all `valid-*` and
  reject all `invalid-*` fixtures (strip `_reject_reason` first; the three semantic
  fixtures are rejected by a `validate()` function mirroring the server's rules);
  packed-points codec round-trips and rejects a length mismatch; error envelope
  parses; sync cursor is passed back verbatim.
- Instrumented test on the CI emulator (API 34, one test class only): Room opens at
  version 1 and `PairingScreen` renders. Add gate `android-instrumented` only if the
  emulator lane stays under ~6 minutes; otherwise run it in `release.yml` and record
  why in the PR.
- UI-smoke asset for the Operator: `smoke/android-pairing.md`, a manual checklist
  (sideload, pair, Check shows version, Ping reaches `done`) with expected screenshots
  described in words. Browser smoke does not apply to a native client.

## Acceptance conditions

- [ ] Kill-switch / dark-launch flag (default OFF) for this net-new feature:
      not applicable to the pairing screen itself (it is the app's only screen);
      instead `BuildConfig.PING_ENABLED` gates the Ping button, default ON in debug
      and OFF in release once Stage 6 lands. Record this exception in the PR.
- [ ] UI-smoke "observably-works" check authored for any user-facing surface
      (`smoke/android-pairing.md`).
- [ ] Additive migration only (no destructive schema change): Room v1 is the first
      schema; `fallbackToDestructiveMigration` forbidden in release builds.
- [ ] `v0.0.2` tag attaches a signed APK to the GitHub Release.
- [ ] On the tablet, on the tailnet: pair with a minted token, Check shows the
      staging version, Ping round-trips to `done`.
- [ ] Existing suite stays green; CI all-green

## Pipeline test: YES
