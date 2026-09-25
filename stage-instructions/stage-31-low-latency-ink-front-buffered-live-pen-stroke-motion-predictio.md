# Stage 31: Low-latency ink: front-buffered live pen stroke, motion prediction, unbuffered input, responsive smoothing (toggles, default off)

- **Type:** feature
- **Depends on:** 28
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/67
- **Design:** SPEC §9.2 (ink capture requirements, esp. (4) "one-euro … without adding
  latency" and (5) "render the in-progress stroke to a separate overlay"), SPEC §12 Phase 0
  acceptance ("you'd choose it over paper"); contract `ink-storage` (stored points are the
  filtered real samples; format unchanged); `feature-assessments/low-latency-ink-assessment.md`.
  Code: `android/app/src/main/java/com/inkwell/ink/InkView.kt`, `ink/StrokeBuilder.kt`,
  `ink/OneEuroFilter.kt`, `render/LayerRenderer.kt`, `ui/CanvasScreen.kt` (hosts `InkView`
  via `AndroidView`), `ui/PairingScreen.kt` (the Settings screen made reachable in stage 28).

## Objectives

The owner reports the Lenovo Idea Tab Pro's own notes app feels much smoother to write in
than Inkwell. Inkwell's live pen stroke goes through the normal View pipeline: every
`ACTION_MOVE` calls `invalidate()`, and the whole scene redraws. That puts the stroke 2–3
frames behind the nib. There is no motion prediction, input is batched to vsync, and the
one-euro filter's `minCutoff = 1.0 Hz` visibly trails slow writing.

After this stage the owner can switch on, in Settings, a **low-latency pen path**:
- the in-progress pen stroke is drawn to a **front-buffered** overlay;
- a few milliseconds of **motion prediction** are drawn ahead of the nib, and never stored;
- input is **unbuffered**.

Separately, the owner can pick a **Responsive** smoothing preset. The two are separate
switches so each can be A/B'd against the native app on the tablet. Both default **off**:
the current behaviour is unchanged until the owner opts in.

## What to build

1. **Dependencies.** Add these to `gradle/libs.versions.toml` and `app/build.gradle.kts`.
   Both were checked on 2026-09-25: minCompileSdk 34 and AGP ≥ 8.1.1, so they fit
   today's compileSdk 34 / AGP 8.5.2 / Kotlin 1.9.24 with no toolchain bump. Update the
   Gradle dependency lockfiles the repo commits.
   - `androidx.graphics:graphics-core:1.0.4`, for `CanvasFrontBufferedRenderer` (API 29+; minSdk is 31).
   - `androidx.input:input-motionprediction:1.0.0`, for `MotionEventPredictor`.
   **Do not** adopt Jetpack Ink (`androidx.ink`). It is built with Kotlin 2.0 and brings
   its own stroke and brush model, which would fork our `ink-storage` pipeline. See the
   assessment.

2. **Front-buffered wet layer (pen only).**
   - Add a transparent `SurfaceView` overlay stacked above `InkView` inside the same
     `AndroidView` host, for example a `FrameLayout` holding `InkView` plus a new
     `WetInkLayer`.
   - Drive it with `CanvasFrontBufferedRenderer`. While the pen tool is drawing, each
     batch of new filtered points from `StrokeBuilder` is drawn **incrementally** in the
     front-buffer callback. Reuse the existing render-time width function
     `width_cu * (0.3 + 0.7 * p)` and the current `CanvasTransform`, so the wet stroke
     matches the dry stroke pixel for pixel.
   - On pen-up, commit through the existing `commitStroke()` path. Hand off wet to dry
     (`commit()` / clear the front buffer) only after `InkView` has drawn the new
     committed stroke, so there is **no flicker, gap or double-darkening** at the handoff.
   - **Marker and eraser keep the existing path.** Marker is translucent (`MARKER_ALPHA`),
     so incremental front-buffer segments would darken where they overlap. The eraser
     does not draw.
   - Pan and zoom: finger gestures while a stroke is wet must not leave a misplaced wet
     stroke. Clear or re-render the wet layer on any transform change.
   - When the surface is unavailable (not yet created, destroyed on rotation or
     backgrounding), fall back to the existing overlay path for that stroke.

3. **Motion prediction.**
   - Record every event into a `MotionEventPredictor`.
   - While the low-latency path is on, draw the predicted tail on the wet layer only,
     and replace it every frame.
   - **Predicted points are never fed to `StrokeBuilder`, never persisted and never
     exported.** The stored stroke equals what the same input produces with prediction
     off (contract `ink-storage`: stored points are the filtered real samples).
   - No predicted tail survives pen-up.

4. **Unbuffered input.** While the low-latency path is on, call
   `requestUnbufferedDispatch(event)` on the stylus `ACTION_DOWN`. Keep iterating
   `historySize` (SPEC §9.2(2)) exactly as today.

5. **Responsive smoothing preset.**
   - Add a `SmoothingPreset` with two values:
     - `STANDARD`: today's `minCutoff = 1.0`, `beta = 0.007`, the default.
     - `RESPONSIVE`: less lag at slow speeds. Start from `minCutoff ≈ 3.0`,
       `beta ≈ 0.02`, record the final values and why in the assessment, and keep jitter
       invisible on the SPEC §9.2 slow-diagonal check.
   - Applied through the existing `InkView.minCutoff` / `beta` knobs.
   - Changing the preset changes the *geometry* of strokes captured afterwards. It does
     not change the storage *format*. Existing strokes are untouched.

6. **Kill-switches and settings.**
   - A compile-time `BuildConfig.LOW_LATENCY_INK` gates whether the controls exist at
     all. It is true in debug and in release, by documented exception like
     LIBRARY/FORMALIZE, because the owner tests on the release APK.
   - Two **runtime** switches on the Settings screen (`PairingScreen`), under an "Ink"
     heading, persisted in a small `InkPrefs` (`SharedPreferences`, not the token store):
     - "Low-latency pen (experimental)", default **off**
     - "Smoothing: Standard / Responsive", default **Standard**
   - `CanvasScreen` reads them when a canvas opens. Applying on the next canvas open is acceptable.

7. **Debug latency readout (debug builds only).** Extend the existing debug overlay to
   show the rolling average of (frame presentation time − `MotionEvent.eventTime`) for
   the live stroke. The goal is a number to compare, not a perfect measurement.

## Interface contracts

- **Exposes:** nothing to other stages or the server. It is client-internal rendering
  plus two local preferences.
- **Consumes:** `ink-storage` (unchanged: same stroke format, stride 5, Room schema v4, no
  migration). `coordinate-mapping` is unaffected, because export renders committed strokes only.
- No new contract. No ADR: this changes how the in-progress stroke is drawn, not a seam or
  stack decision. ADR-0001's stack is extended by two AndroidX libraries.

## Testing requirements

- **Unit** (`testDebugUnitTest`):
  - `SmoothingPreset` maps to the documented parameters.
  - `InkPrefs` defaults are off / Standard.
  - A pure-Kotlin seam around prediction proves predicted points never reach
    `StrokeBuilder`. For example, a `LiveStrokeSink` fed real and predicted points, with
    only the real ones going to the builder.
- **Instrumented** (`androidTest`; do not assert front-buffer pixels, because emulators
  may not present front buffers):
  - Extend `InkCaptureInstrumentedTest`'s synthetic stylus stroke (DOWN + MOVE with
    history + UP). With low-latency **on**, it persists exactly one stroke with the
    **same point count and points** as with it **off**.
  - With low-latency on, the marker and eraser still behave as today.
  - The existing Canvas, Ink and Toolbar instrumented tests stay green with both switches off.
- **UI-smoke asset (owner, real tablet):** `smoke/android-low-latency-ink.md`. It is a
  side-by-side feel test against the Idea Tab Pro's native notes app:
  - write the same sentence, a slow diagonal and a fast flick in each app;
  - compare Inkwell with Low-latency off vs on, and Smoothing Standard vs Responsive;
  - check each of these, with a place to record the result:
    - no visible gap or flicker when the pen lifts;
    - no ghost predicted tail after lift;
    - a stroke written with low-latency on looks identical after a reopen;
    - palm rejection still holds;
    - marker, eraser, pan/zoom and rotation mid-session behave as before;
  - record the debug latency readout for each setting;
  - a verdict row: "prefer Inkwell / native / no difference".

## Acceptance conditions

- [ ] Kill-switch / dark-launch flag (default OFF) for this net-new feature (`LOW_LATENCY_INK` compile gate + runtime switches default off / Standard)
- [ ] UI-smoke "observably-works" check authored for any user-facing surface (`smoke/android-low-latency-ink.md`)
- [ ] Additive migration only (no destructive schema change): no Room migration at all; stroke format unchanged
- [ ] Predicted points provably never persisted (unit and instrumented equality test)
- [ ] Existing suite stays green; CI all-green, plus the on-demand instrumented lane green on the branch

## Pipeline test: NO
