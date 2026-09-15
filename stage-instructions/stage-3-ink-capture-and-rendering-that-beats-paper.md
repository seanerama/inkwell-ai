# Stage 3: Ink capture and rendering that beats paper

- **Type:** feature
- **Depends on:** 2
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/3
- **Design:** SPEC §9.2, §4.3–4.4, contract `ink-storage`, ADR-0001

## Objectives

SPEC Phase 0. One canvas, no server involvement: pen input with pressure and tilt,
eraser, pan/zoom, persistence in Room, and a feel that the user would choose over
paper for a short note. Every later phase inherits this; if the feel test fails, this
stage is not done.

## What to build

- `ink/`: a custom `InkView` handling raw `MotionEvent`, iterating
  `historySize` samples, rejecting `TOOL_TYPE_FINGER` while a stylus is in range,
  one-euro filter (tunable `min_cutoff`, `beta`), stroke builder emitting contract
  `ink-storage` points in canvas units.
- `render/`: committed-stroke bitmap cache per layer plus a live overlay for the
  in-progress stroke; width `width_cu * (0.3 + 0.7 * p)`; pan/zoom via two-finger
  gestures with the transform applied to both cache and overlay; eraser as
  stroke-level hit test on the denormalised bbox then segment distance.
- `data/`: `CanvasRepository` creating a default 2480×3508 canvas with one
  `user/ink` layer on first launch; stroke insert on pen-up; load on open.
- `ui/`: `CanvasScreen` with a minimal toolbar (pen, marker, eraser, three colors,
  undo last stroke) replacing `PairingScreen` as the launch screen; pairing moves to
  a settings entry.
- Debug overlay (debug builds only) showing sample rate, filter latency, and
  dropped-sample count so the feel test has numbers.

## Interface contracts

- **Exposes:** `InkView`, `StrokeBuilder`, `LayerRenderer`, `CanvasRepository`.
- **Consumes:** contract `ink-storage` (Room v1 from Stage 2). No server contract.

## Testing requirements

- JVM unit tests: one-euro filter is monotone on a ramp and does not lag more than
  one sample on a step; stroke builder preserves historical samples in order and
  timestamps are non-decreasing; finger events are dropped while a stylus is in
  range; eraser hit test finds a stroke crossing a point and misses one that does not;
  stored points read back equal the filtered input.
- Instrumented (emulator): injecting a synthetic stylus `MotionEvent` sequence with
  history produces one stroke with the expected point count and persists it.
- Manual feel test on the device (the acceptance): slow diagonal line shows no
  segmentation; a fast flick is continuous; a resting palm makes no ink. Recorded in
  `smoke/android-ink.md` with the debug-overlay numbers observed.

## Acceptance conditions

- [ ] Kill-switch / dark-launch flag (default OFF) for this net-new feature:
      `BuildConfig.INK_ENABLED`, default ON (the app has no purpose with it off);
      when OFF the launch screen is the settings/pairing screen. Record the default
      in the PR.
- [ ] UI-smoke "observably-works" check authored for any user-facing surface
      (`smoke/android-ink.md`, including the three feel-test results).
- [ ] Additive migration only (no destructive schema change): no Room version bump
      expected; if one is needed it ships a `Migration`.
- [ ] Ink survives app restart; undo removes only the last stroke.
- [ ] Existing suite stays green; CI all-green

## Pipeline test: NO
