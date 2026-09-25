# Assessment: off-page ink and making the low-latency defaults on (stages 32, 33)

- **Requests (owner, 2026-09-25, after the v0.0.20 feel test):**
  1. "I like the responsive and low latency, let's make that the default."
  2. "If I zoom out the canvas size that I can write on stays the same… if I draw outside
     of that canvas, it deletes whatever I write when I lift the pen."
- **Decisions:**
  - **ACCEPT** (2) as **stage 32 (bug)**: show the page and make what you see what stays.
  - **ACCEPT** (1) as **stage 33 (chore)**: flip the defaults, after closing the two PR #68
    review gaps.
  - Stage 33 depends on 32. Both touch `InkView` and `WetInkLayer`, so they run in series.

> **Superseded (2026-09-25, same day):** the owner does not want a fixed page ("expand as
> needed, similar to how Visio works"). ADR-0014 makes canvases grow by pages. The stage 32
> fix described below (refuse off-page starts, clip at the page edge) is **replaced** by
> `feature-assessments/expandable-canvas-assessment.md`: stage 32 was repurposed as the
> page-grid foundation, and stages 34 and 35 were added. The claim/reality table below still
> holds as the diagnosis. The stage 33 notes still apply, except that stage 33 now depends
> on 31.

## Claim / reality for the bug (verified 2026-09-25)

| Claim | Checked | Reality |
|---|---|---|
| The writable area is fixed | `InkView.kt:116-117` 2480 × 3508 CU; SPEC §4.2 A4; SPEC §1.2 "Infinite canvas" is a v1 non-goal | true, by design |
| You can zoom the page smaller than the screen | `CanvasTransform.kt:20` `minScale = 0.1` | true |
| The page edge is visible | no page fill, edge or surround drawing in `InkView`/`LayerRenderer` | **false**: page and surround look identical |
| Ink is deleted | `StrokeBuilder` has no bounds filter; points are stored as captured | **false**: it is stored but invisible |
| Why it vanishes on lift | the live stroke draws unclipped (View path and `WetInkLayer`), while committed ink renders from a page-sized cache (`LayerRenderer.kt:137-139`) | a mismatch between the live and committed rendering |
| Stage 31 caused it | the pre-31 View path also drew the live stroke unclipped | **no**: an older bug, exposed by zooming out |
| The contract allows off-page points | `contracts/ink-storage.md:31`: "may exceed during pan; clamp at render" | yes, so the fix is at render time, not in storage |
| The agent sees off-page ink | `CanvasExporter` exports the page only | no |

## Options for the bug

1. **Grow the canvas when writing past the edge (infinite canvas).** This is a v1 non-goal
   (SPEC §1.2). It needs a canvas-size or multi-page model change: a contract and ADR,
   export framing, and SPEC §13 open question 3 (multi-page). **Deferred.** It is a product
   decision for the owner, not a bug fix.
2. **Clamp or drop stored points at the edge.** This changes geometry, breaks the stage 31
   equality guarantee, and goes beyond the contract's "clamp at render". **Rejected.**
3. **Show the page, refuse off-page starts, clip the live stroke at the edge. Chosen**,
   because it:
   - makes the fixed page legible;
   - makes live rendering equal committed rendering;
   - keeps storage and contracts as they are.

## Known leftover

Strokes already written entirely off the page on the tablet stay in Room, invisible, with
no migration and no deletion. The eraser can still hit them when used off the page. Only a
count query on the tablet database would tell us whether any exist. If they turn out to
matter, a one-off cleanup could be planned.

## Stage 33 notes

- The defaults flip only affects preferences that were never set. The owner's explicit
  choices, already on, stay as they are.
- PR #68 review follow-up 1 (the instrumented prediction-isolation test never ran the
  predictor) is a precondition: once the fast path is the default, its isolation guarantee
  must be proven on the path that actually runs.
- Follow-up 2 (wet paint alpha) is a one-line hardening fix and goes in the same stage.
- Review follow-ups 3–6 (pressure-margin fringes, softening when zoomed, allocation per
  event, detached callback) stay non-blocking. Revisit them if the owner notices them on
  the tablet.
