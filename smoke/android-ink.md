# UI-smoke: Android ink capture + rendering (the feel test)

Manual "observably-works" check for the **Operator**, run on the real tablet (Lenovo
Idea Tab Pro, Android 14) with the active stylus. This is the acceptance for Stage 3:
if the feel test fails, the stage is not done (SPEC §9.2). Browser smoke does not
apply to a native client (ADR-0001), so this human pass is the replacement. Run it on
every APK that changes ink capture or rendering.

## Preconditions

- A **debug** APK is installed (the debug build shows the on-canvas debug overlay and
  keeps the numbers visible). `assembleDebug`, or a debug build from the Release page.
- The ink kill-switch is ON: `BuildConfig.INK_ENABLED` defaults to **ON** in both
  build types, so the app opens straight onto the **canvas**. (If a build has it
  flipped OFF, the app opens on the settings/pairing screen and there is no canvas to
  test — that is the expected OFF behaviour, not a failure of this smoke.)
- The stylus is paired/charged and the palm-rejection setting on the tablet is at its
  default.

## Screen orientation

1. **Launch.**
   - Open **Inkwell AI**.
   - *Expected screenshot A:* the **canvas** fills the screen with a thin toolbar
     across the top — **Pen**, **Marker**, **Eraser**, three color swatches (black,
     blue, red), an **Undo** button on the right, and a **Settings** text button. The
     canvas area below is blank/white. In a debug build a small translucent box in the
     top-left corner shows three lines: `sample rate`, `filter latency`,
     `dropped samples`.

## Feel test 1 — slow diagonal line (no segmentation)

2. Select **Pen**, black. Draw a **slow** diagonal line corner-to-corner, taking ~3-4
   seconds, pressing lightly then harder halfway.
   - *Expected:* one smooth line with **no visible straight-segment facets** ("polygon"
     look) anywhere, and the line gets visibly **thicker where you pressed harder**
     (width modulation `width_cu * (0.3 + 0.7 * p)`).
   - *Expected screenshot B:* a smooth diagonal with a visible thin→thick→thin taper.
   - *Debug overlay during the draw:* `sample rate` should read roughly the digitizer
     rate (**~120-240 Hz** on this tablet; anything **≥ 90 Hz** is fine). `filter
     latency` should be **small — low tens of microseconds** (well under 1000 us) and
     must not climb as the stroke grows. `dropped samples` stays **0** for a clean
     stylus stroke.

   Operator result (fill in):
   - [ ] No segmentation: __________
   - [ ] Pressure changes width: __________
   - sample rate observed: ______ Hz
   - filter latency observed: ______ us
   - dropped samples observed: ______

## Feel test 2 — fast flick (continuous)

3. With **Pen**, make several **fast** flicks across the canvas (as fast as you can
   move the stylus).
   - *Expected:* each flick is **one continuous stroke** — no gaps, no dashed look, no
     corner-cutting where the fast motion was batched between frames (this is the
     historical-sample test: batched samples are captured, not dropped).
   - *Expected screenshot C:* a fast S-shaped flick rendered as one unbroken curve.
   - *Debug overlay:* `filter latency` stays small even on the fast stroke (the
     one-euro filter opens its cutoff on speed, so a flick is **not** lagged); `dropped
     samples` still **0**.

   Operator result (fill in):
   - [ ] Flick is continuous (no gaps/facets): __________
   - filter latency on flick: ______ us
   - dropped samples observed: ______

## Feel test 3 — resting palm (no ink)

4. Rest your **palm** flat on the canvas with the stylus **hovering just above** the
   surface (not touching), then bring the stylus down and draw a short mark **while
   your palm stays down**.
   - *Expected:* the palm leaves **no ink at all**; only the stylus draws. Finger/palm
     contact is rejected while the stylus is in range (§9.2(3)).
   - *Expected screenshot D:* a single short stylus mark and **no** stray blobs or
     smudges where the palm rested.
   - *Debug overlay:* `dropped samples` **increments** while the palm is down (the
     rejected finger/palm touches are counted), confirming rejection is active rather
     than the palm simply not registering.

   Operator result (fill in):
   - [ ] Palm made no ink: __________
   - [ ] `dropped samples` increased while palm was down: __________
   - dropped samples observed: ______

## Eraser, undo, pan/zoom, persistence

5. **Eraser.** Draw two crossing strokes; select **Eraser**; drag across **one** of
   them.
   - *Expected:* the whole crossed stroke disappears in one pass (stroke-level erase);
     the other stroke is untouched.
   - Operator result: [ ] correct stroke erased, other intact: __________

6. **Undo.** Draw three strokes; tap **Undo** three times.
   - *Expected:* strokes vanish **most-recent first**, one per tap; a fourth tap does
     nothing. Undo removes only the last stroke.
   - Operator result: [ ] undo removes last only: __________

7. **Pan/zoom.** With **two fingers**, pinch to zoom and drag to pan.
   - *Expected:* committed ink and any in-progress stroke move/scale **together**;
     ink stays crisp (not a blurry blow-up of a low-res cache); the stylus keeps
     drawing at the zoomed scale in the right place.
   - Operator result: [ ] pan/zoom moves cache + overlay together: __________

8. **Persistence.** Draw a stroke, fully close the app (swipe from recents), reopen.
   - *Expected:* the ink is still there (loaded from Room on open).
   - *Expected screenshot E:* the same canvas content after a cold restart.
   - Operator result: [ ] ink survived restart: __________

## Settings reachability (launch-screen swap)

9. Tap **Settings** in the toolbar.
   - *Expected:* the **pairing** screen (Server URL, Device token, Check/Ping) with a
     "< Back to canvas" affordance; **Back** returns to the canvas. Pairing is no
     longer the launch screen but remains reachable.
   - Operator result: [ ] pairing reachable from settings and returns: __________

## Page grid (stage 32, ADR-0014)

The canvas is now a grid of pages. Nothing grows yet (that is stage 34), but the page
edges are visible, and ink written off the page before this release is shown again.

10. **Zoom out: page edges are visible.** Pinch out until the whole page is on screen.
    - *Expected:* the page is white paper with a thin edge and a soft shadow; the area
      around it is a distinct grey surround. If the device is in dark theme, the surround
      follows the theme and the page stays white.
    - *Expected screenshot F:* the zoomed-out page with its edge and surround.
    - Operator result: [ ] page edges and surround visible: __________
11. **Off-page ink is visible again.** On a device upgraded from v0.0.20 or earlier, open a
    canvas where ink was written beyond the page edge (it used to vanish on pen-up).
    (To prepare one on a fresh install: install the previous release, zoom out, write past
    the right or bottom edge, then install this build over it.)
    - *Expected:* the canvas now shows extra pages (right / below, or left / above) and
      the previously invisible ink sits on them; nothing on page 1 moved.
    - *Expected screenshot G:* the multi-page canvas zoomed out.
    - Operator result: [ ] off-page ink shown with its extra pages: __________
12. **Write, erase and pan feel as before.** On page 1 (and on an extra page, if you have
    one): write several lines with the pen, one with the marker, erase a stroke, undo,
    pan and pinch.
    - *Expected:* no gap or flicker when a pen stroke lifts (the dry copy replaces the
      wet one seamlessly), erase and undo remove exactly one stroke, pan/zoom is smooth,
      and ink looks as sharp as before at normal zoom.
    - Operator result: [ ] writing / erasing / panning unchanged: __________
13. **Memory check.** With **Developer options → Memory** (or Android Studio's profiler)
    open a canvas with ink on many pages (the canvas from step 11), zoom out and in and pan
    around all of it for about a minute.
    - *Expected:* no stutter, no crash; app memory settles rather than climbing (the ink
      tile cache is capped at 96 MB). In a debug build, logcat tag `InkView` prints
      `memoryClass=… tileBudget=96 MB` once — note the values here.
    - Operator result: [ ] no stutter / crash; memoryClass = ______ MB: __________

## Pass criteria

- [ ] Feel test 1 — slow line, no segmentation, pressure width (screenshot B).
- [ ] Feel test 2 — fast flick continuous (screenshot C).
- [ ] Feel test 3 — resting palm makes no ink; dropped-sample count rises (screenshot D).
- [ ] Eraser removes the crossed stroke only.
- [ ] Undo removes only the last stroke.
- [ ] Pan/zoom transforms committed cache and live overlay together.
- [ ] Ink survives an app restart (screenshot E).
- [ ] Page grid: page edges visible when zoomed out (screenshot F); off-page ink from an
      older release shown on its extra pages (screenshot G); writing, erasing and panning
      unchanged; no stutter or crash panning a many-page canvas (steps 10–13).
- [ ] Debug overlay numbers are in the expected ranges (sample rate ≥ 90 Hz, filter
      latency low tens of us, dropped samples 0 for clean stylus / rising under palm).

## Kill-switch note

`BuildConfig.INK_ENABLED` gates the feature and defaults **ON in both build types**
(the app has no purpose with ink off). When flipped **OFF**, the launch screen becomes
the settings/pairing screen and no canvas is shown — that is the dark-launch state,
not a smoke failure.

---

**Operator:** ______________________   **Date:** ____________   **APK/tag:** ____________

**Overall result:** ☐ PASS   ☐ FAIL

**Notes / attached screenshots:**
