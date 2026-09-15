# UI-smoke: Android canvas export + fixture highlight (debug build)

Manual "observably-works" check for the **Operator**, run on the real tablet (Lenovo
Idea Tab Pro, Android 14) with the active stylus. This is the acceptance for Stage 4's
user-facing (debug-only) surface: the export preview and the fixture render. Browser
smoke does not apply to a native client (ADR-0001), so this human pass is the
replacement. Run it on every debug APK that changes export, coordinate mapping, or
annotation rendering.

> **Export dimensions — the "1108" vs "1109" note (issue #9).** The exporter follows
> the frozen `coordinate-mapping` FORMULA: `scale = 1568 / max(width_cu, height_cu)`,
> `export_w = round(width_cu * scale)`. For the default 2480×3508 canvas this is
> **1109 × 1568** (`round(2480 * 1568/3508) = round(1108.506) = 1109`). The stage
> instruction, the acceptance checklist, and the `device-api` `POST /jobs` example print
> **1108×1568** — that is a known off-by-one between the prose and the round() formula,
> tracked in GitHub issue #9. **The formula is authoritative; the export preview shows
> `1109 × 1568`.** Seeing 1109 (not 1108) is the PASS here.

## Preconditions

- A **debug** APK is installed (`assembleDebug`, or a debug build from the Release
  page). The two Stage-4 actions are gated by `BuildConfig.DEBUG` and appear **only** in
  debug builds — a release build shows neither button (no release-visible surface yet;
  the checklist-sanctioned kill-switch treatment for this stage).
- The ink kill-switch is ON (`BuildConfig.INK_ENABLED` default), so the app opens on
  the **canvas**.

## Screen orientation

1. **Launch.** Open **Inkwell AI**.
   - *Expected:* the canvas fills the screen with the toolbar across the top. On the
     right, before **Undo**, a debug build shows two extra buttons: **Export preview**
     and **Render fixture**. (In a release build these two buttons are absent — that is
     the expected release behaviour, not a failure of this smoke.)

## Test 1 — export preview shows 1109×1568

2. Draw a few strokes anywhere on the (default, empty) canvas, then tap **Export
   preview**.
   - *Expected:* a dialog titled **Export preview** opens. Its first line reads
     **`1109 × 1568 px`** followed by the PNG size in KB. Below it is the flattened PNG
     — your strokes on a solid **white** background, at the canvas aspect ratio.
   - *Expected screenshot A:* the dialog showing `1109 × 1568 px` and the white-backed
     PNG of the strokes.
   - PASS requires the dimensions read **1109 × 1568** (the formula; the "1108" in the
     acceptance is issue #9). Tap **Close**.

   Operator result (fill in):
   - [ ] Dimensions read 1109 × 1568: __________
   - [ ] Background is opaque white, strokes visible: __________
   - PNG size shown: ______ KB

## Test 2 — fixture highlight lands where expected

3. Tap **Render fixture**.
   - *Expected:* a single translucent filled **highlight** region appears over the
     canvas in the space accent color at ~70% opacity (visually distinct from user ink:
     no pressure taper, slightly transparent). It sits in the upper-left quadrant.
   - *Placement check (from `valid-full-vocabulary.json`, annotation `a1`,
     points `[[0.10,0.20],[0.40,0.20],[0.40,0.30],[0.10,0.30]]`):* the highlight's box
     spans horizontally from **10%** to **40%** of the canvas width and vertically from
     **20%** to **30%** of the canvas height. Eyeball that the left edge is ~1/10 in
     from the left, the right edge ~40% across, the top ~1/5 down, and the box is a
     wide, short band. The button now reads **Hide fixture**.
   - *Expected screenshot B:* the accent-colored highlight band in the upper-left
     quadrant.

   Operator result (fill in):
   - [ ] Highlight is translucent accent color (~70%), distinct from ink: __________
   - [ ] Box spans ~10–40% wide, ~20–30% down: __________

4. Tap **Hide fixture**.
   - *Expected:* the highlight disappears; user ink is untouched.

## Notes

- The export preview and fixture render are **debug-only** (`BuildConfig.DEBUG`). They
  are the dark-launch treatment for this net-new feature: no release-visible surface
  until Stage 6 wires the real send/receive flow.
- The exporter never sends a PNG over 2 MB: if the flattened PNG exceeds 2 MB it is
  re-encoded once with palette reduction, and if still too large the preview shows a
  user-visible "too detailed to send" message instead of an image (contract
  `coordinate-mapping` §Export; `device-api` 413).
