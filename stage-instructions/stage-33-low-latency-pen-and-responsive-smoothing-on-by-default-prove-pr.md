# Stage 33: Low-latency pen and Responsive smoothing on by default; prove prediction isolation on the wet path

- **Type:** chore
- **Depends on:** 31
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/70
- **Design:** `feature-assessments/low-latency-ink-assessment.md` ("a later small stage flips
  the defaults"); PR #68 review follow-ups 1 and 2; contract `ink-storage`. Code:
  `ink/InkPrefs.kt`, `ink/SmoothingPreset.kt`, `ink/WetInkLayer.kt`,
  `androidTest/.../LowLatencyInkInstrumentedTest.kt`, `InkCaptureInstrumentedTest.kt`.

## Objectives

_Re-pointed 2026-09-25: this stage originally depended on the old stage 32 only so the two would not edit the same files at once. ADR-0014 repurposed stage 32, so this stage now builds on 31._

The owner ran the stage 31 feel test on v0.0.20 (2026-09-25): "I like the responsive and low
latency, let's make that the default". After this stage, a fresh install writes with the
low-latency pen and the Responsive smoothing preset. Both switches stay in Settings → Ink
so either can be turned off.

Before the default flips, close the two review gaps from PR #68:
1. The instrumented "predicted points never persisted" check must actually run the
   predictor through the wet path.
2. The wet paint must force alpha to 255 the way the dry path does.

## What to build

1. **Defaults.**
   - `InkPrefs.DEFAULT_LOW_LATENCY_PEN = true` and `SmoothingPreset.DEFAULT = RESPONSIVE`.
   - An explicit stored choice always wins. Installs where the owner already turned them on
     are unaffected.
   - An owner who explicitly turned a switch off keeps it off, so write the preference only
     on user action, never on read.
   - Update the Settings copy: drop "(experimental)", and keep a one-line explanation of each switch.
   - The compile-time `LOW_LATENCY_INK` gate stays as the kill switch.
2. **Wet paint alpha:** force pen alpha to 255 on the wet layer (`WetInkLayer.kt` ~197, ~227),
   matching `LayerRenderer`, so a future translucent pen colour cannot mismatch at the handoff.
3. **Prove isolation on the wet path.** Make an instrumented test that genuinely exercises
   prediction:
   - host `InkView` and its wet layer in a real activity window;
   - feed a synthetic stylus gesture with realistic timestamps through the view, so
     `MotionEventPredictor` records it;
   - assert through a test hook that the predictor produced at least one predicted sample
     (for example, a debug counter on the sink);
   - then assert the committed stroke's points equal the same gesture's points with
     prediction off.
   - If the emulator cannot create a front-buffer surface, the test must **fail loudly with
     a clear message**, not silently skip. If CI's emulator genuinely cannot support it,
     decide with the reviewer, and record the decision, whether to exercise the predictor
     without the surface (the sink path still uses it) and log which mode ran.

## Interface contracts

- **Exposes:** nothing new. **Consumes:** `ink-storage`, unchanged. No migration.

## Testing requirements

- **Unit:** default values (on, Responsive); an explicit stored `false` or `standard` still wins.
- **Instrumented:** the strengthened prediction-isolation test above, plus a Settings test
  showing both switches on for a fresh install.
- **UI-smoke:** update `smoke/android-low-latency-ink.md` "setup" to say both switches are
  now on by default, and add a row checking that turning each off still works.

## Acceptance conditions

- [ ] Exit state: a fresh install has the low-latency pen on and Responsive smoothing; explicit user choices persist
- [ ] Instrumented test proves the predictor ran on the wet path and the stored points are unchanged
- [ ] Wet pen alpha forced to 255
- [ ] Existing suite stays green; CI all-green, plus the on-demand instrumented lane green on the branch

## Pipeline test: NO
