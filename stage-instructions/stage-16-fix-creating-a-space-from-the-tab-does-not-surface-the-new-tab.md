# Stage 16: Fix: creating a space from the + tab does not surface the new tab (instrumented lane red on v0.0.13)

- **Type:** bug
- **Depends on:** 15
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/37
- **Design:** stage 15 spec (New space flow), ADR-0010 (server-first edits, mirror refresh)

## Objectives

The v0.0.13 release's instrumented lane is red on exactly one test:
`SpaceSettingsInstrumentedTest.plus_tab_creates_a_space_that_becomes_the_selected_tab`
(`ComposeTimeoutException` after 5 s, deterministic across two runs). After this stage
the "+" tab flow is proven: creating a space surfaces its tab and selects it, on the
emulator lane and on the tablet.

## What to build

- Diagnose which `waitUntil` times out (add a message to each wait so the report says
  which). Candidates: the new tab not appearing after `POST /spaces` →
  `upsertSpace` → `SpaceSync.refresh()` (tab list state not recomposed, or refresh
  overwriting with a stale list), `activeSpaceId` not switched to the created id, or
  the test's id/tag derivation (`space-cooking`) not matching what the view-model
  produces.
- Fix the defect in `SpaceSettingsViewModel.createSpace` / `LibraryViewModel` /
  `SpaceTabBar` as found. If the defect is test-only, fix the test and say so in the
  PR.
- No contract or schema change.

## Interface contracts

- **Exposes:** nothing new.
- **Consumes:** `device-api` `POST /spaces`, `GET /spaces` (unchanged).

## Testing requirements

- The failing test passes on the emulator lane: run the on-demand `instrumented.yml`
  workflow on the stage branch **before** opening the PR for review and link the green
  run in the PR (the CI gate does not run instrumented tests).
- A JVM unit test for the create flow (fake repository → tab list contains the new
  space and it is active) so the regression is caught without an emulator.

## Acceptance conditions

- [ ] Regression test in place: the instrumented test passes and a JVM test covers the create-then-select flow
- [ ] Green on-demand instrumented run linked in the PR
- [ ] Root cause stated in the PR (product defect vs test defect)
- [ ] Existing suite stays green; CI all-green

## Pipeline test: NO
