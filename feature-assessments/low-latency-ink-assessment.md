# Assessment: low-latency ink (stage 31)

- **Request (2026-09-25, owner):** the Lenovo Idea Tab Pro's native notes app is "much more
  smooth when writing". Can we write in the native app and share into Inkwell, or reverse-engineer
  how it tracks the pen?
- **Decision:** **ACCEPT** option 2 as stage 31: make Inkwell's own pen path low-latency with
  public AndroidX libraries, behind switches that default off. We **decline** to
  reverse-engineer the vendor app, and we **defer** "share into Inkwell" as a separate,
  optional stage.

## Claim / reality (verified against source and Maven, 2026-09-25)

| Claim | Checked | Reality |
|---|---|---|
| Inkwell's live stroke is slow to reach the screen | `ink/InkView.kt` is a plain `View`; every `ACTION_MOVE` calls `invalidate()` and `onDraw` repaints the raster, the committed-ink cache blit, the live stroke and the annotations | true: normal pipeline, 2–3 frames behind the nib |
| Committed strokes are re-rasterised every frame | `render/LayerRenderer.kt` keeps a committed-stroke bitmap **cache**, rebuilt only on change | false: not the cause |
| Historical samples are dropped | `InkView.kt:398` iterates `historySize` | false: kept (SPEC §9.2(2)) |
| Input is delivered unbuffered | no `requestUnbufferedDispatch` anywhere | true: batched to vsync |
| Motion prediction | no `MotionEventPredictor` / `androidx.input` | absent |
| Smoothing adds lag | `OneEuroFilter` `DEFAULT_MIN_CUTOFF = 1.0`, `DEFAULT_BETA = 0.007`, applied before commit | plausible contributor at slow speeds; tunable through `InkView.minCutoff`/`beta` |
| SPEC §9.2(5) "in-progress stroke on a separate overlay" | the live stroke is drawn in the same `onDraw` as everything else | only nominally met; the front-buffered wet layer meets it properly |
| `graphics-core` fits the toolchain | Maven 1.0.4 AAR metadata: `minCompileSdk=34`; kotlin-stdlib 1.8.22; `CanvasFrontBufferedRenderer` API 29+ vs our minSdk 31 | fits: no bump |
| `input-motionprediction` fits | Maven 1.0.0: `minCompileSdk=34`, `minAndroidGradlePluginVersion=8.1.1` vs AGP 8.5.2 | fits |
| Jetpack Ink is the easy route | `ink-authoring` 1.0.0 depends on kotlin-stdlib 2.0.21 and has its own `Stroke`/`Brush` model | rejected for now: forks the `ink-storage` pipeline; revisit after the toolchain refresh (Revisit proposal 11) |
| Marker can use the same fast path | `LayerRenderer.kt:142` marker paints at `MARKER_ALPHA` | no: incremental front-buffer segments darken where they overlap, so marker stays on the existing path |
| Tablet | `smoke/android-ink.md`: Lenovo Idea Tab Pro, Android 14, active stylus | confirmed |

## Options weighed

1. **Write in the native app, share into Inkwell.**
   - Works technically: stage 22 renders PDFs and images as a raster layer, and the agent
     reads an exported image anyway.
   - Costs:
     - the handwriting becomes a bitmap, which cannot be erased or edited per stroke
       (SPEC §1.3 invariant 1);
     - every round trip goes through two apps and a share sheet;
     - agent markup lands on Inkwell's copy, not the native note.
   - **Deferred:** a generic "share a PDF or image into a space" intake is worth a small
     stage of its own, but not as the primary way to write.
2. **Reverse-engineer the vendor app.**
   - Brittle, probably licence-restricted.
   - The techniques are almost certainly the public ones: front buffer, prediction,
     unbuffered input. A private vendor hook would not be portable or stable anyway.
   - **Declined.**
3. **Fix Inkwell's pen path with public libraries.** **Chosen** for these reasons:
   - it keeps vector ink, the `ink-storage` contract and one app;
   - it needs no toolchain bump;
   - it is reversible through the runtime switches.

## Why two switches, default off

The owner is the acceptance judge (SPEC §12 Phase 0 is a feel test), and feel is subjective.
Separate "Low-latency pen" and "Smoothing: Responsive" switches let the owner learn which
change matters, A/B'd against the native app on the same tablet. Defaults leave current
behaviour exactly as it is. If the smoke verdict is positive, a later small stage flips the
defaults and removes the Standard path if it is no longer needed.

## Risks the spec addresses

- **Wet/dry handoff flicker or double-darkening:** clear the front buffer only after the dry
  stroke is drawn.
- **Predicted points leaking into storage or export:** a unit seam and an instrumented
  equality test.
- **Surface lifecycle** (rotation, backgrounding): fall back to the existing path for that stroke.
- **Pan or zoom while wet:** clear or re-render the wet layer.
- **Emulators may not present front buffers:** the tests assert the data path, not
  front-buffer pixels; the feel is judged on the tablet.

## Deferred

- "Share into Inkwell" (ACTION_SEND of a PDF or image into a space, as a raster canvas),
  a candidate stage.
- Jetpack Ink adoption, after the Android toolchain refresh (Revisit proposal 11), and only
  if stage 31 is not enough.
- Front-buffered marker (would need a whole-stroke redraw per frame on the wet layer).

## Chosen RESPONSIVE values (stage 31 build)

`SmoothingPreset.RESPONSIVE` is `minCutoff = 3.0 Hz`, `beta = 0.02`: the starting point,
kept. A simulated slow diagonal (60 CU/s, 240 Hz, 1 CU σ digitizer noise):

| Preset | Lag behind the nib | Perpendicular jitter left |
|---|---|---|
| STANDARD (1.0 / 0.007) | ~41 ms | ~0.21 CU RMS |
| RESPONSIVE (3.0 / 0.02) | ~22 ms | ~0.29 CU RMS (≈0.025 mm, ~1/10 of the 3 CU pen) |

Going further buys little. With beta 0.03 or minCutoff 4.0 the lag drops by ≤ 3 ms
more, and the jitter keeps rising. `SmoothingPresetTest` pins both properties: less lag,
and jitter below 0.5 CU. The owner's slow-diagonal row in
`smoke/android-low-latency-ink.md` is the real check.
