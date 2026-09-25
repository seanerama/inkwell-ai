# UI-smoke: low-latency ink vs the tablet's native notes app (the feel A/B)

Manual "observably-works" check for the **owner**, run on the real tablet (Lenovo Idea
Tab Pro, Android 14) with the active stylus. This is the acceptance for Stage 31. The
question it answers is SPEC §12 Phase 0's: *would you choose Inkwell over the tablet's
own notes app to write in?* Feel is subjective, so the two Stage-31 switches are tested
separately and side by side with the native app. Browser smoke does not apply to a
native client (ADR-0001), so this human pass is the replacement.

## Preconditions

- The compile gate `BuildConfig.LOW_LATENCY_INK` is **ON** (the default in both build
  types), so **Settings** (toolbar ⋮ → **Settings**) shows an **Ink** section with:
  - **Low-latency pen**, a switch with a one-line explanation, **on by default** (since
    stage 33);
  - **Smoothing:** **Standard** / **Responsive**, **Responsive by default** (since stage
    33), with a one-line explanation;
  - the note "Applies the next time you open a canvas."
  On a **fresh install** (or after clearing app data) both are already on: config **D**
  below is what a new owner gets without touching Settings. An install where you changed
  a switch before keeps your explicit choice.
  (If a build has the gate flipped OFF, the Ink section is absent and the canvas uses
  today's pen path. That is the dark-launch state, not a failure of this smoke.)
- A **debug** APK is installed for the latency numbers: only debug builds show the
  on-canvas debug overlay, which gains an `ink latency: N ms (wet|view)` line while
  Low-latency pen is on. Optionally repeat the feel rows on the **release** APK; the
  switches behave identically there, just without the overlay.
- A fresh blank canvas in Inkwell, and a blank page in the native notes app with a
  similar black pen at a similar width.
- After changing a switch, go **Back** and **re-open the canvas** (Library → tile), so
  the new setting applies.

## The four Inkwell configurations

Run every writing test below in the native app and in each Inkwell configuration:

| Config | Low-latency pen | Smoothing |
|---|---|---|
| **A** (pre-stage-31 path) | off | Standard |
| **B** | on | Standard |
| **C** | off | Responsive |
| **D** (default since stage 33) | on | Responsive |

## Writing tests (per configuration)

1. **Sentence.** Write *"The quick brown fox jumps over the lazy dog"* at your normal
   speed, in cursive or print, whichever you usually use.
   - *Watch:* how far the ink trails behind the nib; whether letters look like the
     native app's; any wobble or blobs.
2. **Slow diagonal.** Draw a slow diagonal corner to corner over ~3–4 seconds, pressing
   lightly, then harder halfway (the SPEC §9.2 check).
   - *Expected:* one smooth line with **no visible segmentation** and **no jitter or
     wobble**, and a thin→thick taper where you pressed harder. Responsive trails the nib
     less than Standard; it must not look shakier.
3. **Fast flick.** Several fast S-shaped flicks.
   - *Expected:* each flick is one continuous stroke, with no gaps, no dashes and no cut
     corners. With Low-latency on, the predicted tail can briefly reach a little ahead
     of the nib while you move. It must **never** remain after the pen lifts.

## Checks with Low-latency pen on (configs B and D)

4. **Pen lift: no gap, no flicker.** Write a word, lift, and watch the last stroke.
   - *Expected:* the stroke stays put through the lift: it does not blink, vanish for a
     frame, thicken or darken, or shift position.
5. **No ghost tail.** Flick fast and lift mid-motion.
   - *Expected:* the stroke ends where the pen lifted. No predicted "overshoot" tail stays
     on screen.
6. **Identical after reopen.** Write a short word with Low-latency on. Go **Back** to
   the Library and re-open the canvas (or restart the app).
   - *Expected:* the word looks **exactly** the same, stroke for stroke. The stored
     points are the real samples, not the drawn prediction.
7. **Palm rejection.** Rest your palm on the canvas with the stylus hovering, then write
   while the palm stays down.
   - *Expected:* no palm ink; only the stylus draws (debug overlay: `dropped samples`
     rises while the palm is down).
8. **Marker, eraser, pan/zoom, rotation mid-session.**
   - **Marker:** highlight across written words, crossing its own stroke.
     *Expected:* translucent as before, and the overlap of one stroke with itself is
     **not** darker (the marker keeps the existing path).
   - **Eraser:** drag across one stroke. *Expected:* that whole stroke goes; the others
     stay.
   - **Pan/zoom:** write a word, then immediately pinch and drag with two fingers.
     *Expected:* all ink, including the word just written, moves and scales together.
     No wet copy stays behind at the old position.
   - **Rotation:** rotate the tablet mid-session, then write again. *Expected:* existing
     ink is intact and the pen still draws where the nib is.
9. **Latency readout (debug APK).** While writing the sentence, note the overlay's
   `ink latency` (it is a rolling average). `wet` means the front-buffered layer drew
   the stroke; `view` means the normal View path drew it (Low-latency off, or the
   wet surface was unavailable). Lower is better. The number measures the time until
   the frame is handed to the compositor, so it understates the View path's real delay
   by about a frame.

## Stage 33: the defaults, and turning each switch off

10. **Fresh install.** Clear app data (or install fresh) and open Settings → Ink.
    *Expected:* **Low-latency pen** is on and **Responsive** is selected. Open a canvas
    and write: the debug overlay's `ink latency` path reads `wet`.
11. **Turning each switch off still works.**
    - Turn **Low-latency pen** off, go Back and re-open the canvas, write a word.
      *Expected:* the pen still draws, with no predicted tail ahead of the nib, and the
      debug overlay's `ink latency` path reads `view`, not `wet`.
    - Pick **Standard**, go Back and re-open the canvas, draw the slow diagonal.
      *Expected:* it draws as config A/B did (a little more lag than Responsive, no
      jitter or segmentation).
    - Restart the app and re-open Settings. *Expected:* both still show **off** and
      **Standard**; the defaults never overwrite an explicit choice.

## Results

Mark each cell ✓ (as expected), ✗ (problem, add a note) or a number where asked.

| Check | Native app | A (off / Std) | B (on / Std) | C (off / Resp) | D (on / Resp) |
|---|---|---|---|---|---|
| Sentence: ink keeps up with the nib (1–5, 5 = native-like) | | | | | |
| Slow diagonal: no segmentation, no jitter | | | | | |
| Fast flick: continuous | | | | | |
| Pen lift: no gap or flicker | n/a | | | | |
| No ghost tail after lift | n/a | n/a | | n/a | |
| Identical after reopen | n/a | | | | |
| Palm rejection holds | | | | | |
| Marker / eraser as before | n/a | | | | |
| Pan/zoom right after writing: no stray wet ink | n/a | | | | |
| Rotation mid-session: ink intact, pen still accurate | n/a | | | | |
| Debug `ink latency` (ms, and `wet`/`view`) | n/a | | | | |
| 10. Fresh install shows Low-latency **on** and **Responsive** | n/a | n/a | n/a | n/a | |
| 11. Low-latency turned **off** still draws (path `view`) and stays off after restart | n/a | | n/a | | n/a |
| 11. **Standard** chosen still draws cleanly and stays chosen after restart | n/a | | | n/a | n/a |

**Verdict** (circle one per row):

| Comparison | Verdict |
|---|---|
| Inkwell's best config (__) vs native app | prefer Inkwell / native / no difference |
| Low-latency pen on vs off | prefer on / off / no difference |
| Smoothing Responsive vs Standard | prefer Responsive / Standard / no difference |

## Pass criteria

- [ ] A fresh install starts in config D (Low-latency on, Responsive) (check 10).
- [ ] Turning each switch off still works and the choice persists (check 11).
- [ ] No regression with both switches off (config A behaves exactly as before).
- [ ] With Low-latency on: no gap, flicker or darkening at pen lift, and no ghost tail.
- [ ] A stroke written with Low-latency on is identical after a reopen.
- [ ] Palm rejection, marker, eraser, pan/zoom and rotation behave as before in all
      configs.
- [ ] Responsive: the slow diagonal shows no visible jitter or segmentation.
- [ ] Verdict rows filled in. (Stage 33 flipped both defaults on after the owner's
      stage 31 verdict; see `feature-assessments/low-latency-ink-assessment.md`.)

## Kill-switch note

`BuildConfig.LOW_LATENCY_INK` is the compile-time gate. It is **ON in debug and release**
by documented exception (the owner judges the feel on the release APK). Since stage 33
the two runtime switches default to **on** and **Responsive**; either can be turned off
in Settings. Turning the gate **OFF** is the kill switch: it removes the Ink section, and
every canvas uses the pre-stage-31 pen path with Standard smoothing, whatever the stored
switches say. That is the dark-launch state, not a smoke failure.

---

**Operator:** ______________________   **Date:** ____________   **APK/tag:** ____________

**Overall result:** ☐ PASS   ☐ FAIL

**Notes / attached screenshots:**
