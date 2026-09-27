# UI-smoke: Android expandable canvas (stages 34–36, ADR-0014 §2, §4–§6)

Manual "observably-works" check for the **Operator**, run on the real tablet (Lenovo Idea
Tab Pro, Android 14) with the active stylus. It covers the ghost ring, writing into it to
add pages in any direction, the 8-page cap, the grid surviving a reopen and (stage 35)
agent jobs that send **what is on screen**: Ask about writing on page 2, the "Zoom in to
send" floor, and Formalize from a region. Stage 36 adds **fit to screen**: every canvas
opens with its whole page grid on screen (so Ask on a fresh note sends the whole page
again), and a **Fit** button brings that view back (section 11). Browser smoke does not
apply to a native client (ADR-0001), so this human pass is the replacement.

## Preconditions

- The **release** APK is installed (the signed APK from the Release page). Since stage 35
  `BuildConfig.EXPANDABLE_CANVAS` is **ON in debug and in release** (documented exception);
  flipping it to `false` is the kill switch (see the last section).
- The device is paired with **staging** (Settings → server URL + token) and the server has
  `AGENT_ENABLED` on, so Ask / Mark up / Formalize return results (section 7).
- The Library is on (default): you can create a new canvas from the Library.
- Settings → Ink → **Low-latency pen** is noted; the whole smoke runs twice, once with it
  **on** (the default) and once **off** (see the last section).

## 1. The ghost ring

1. In the Library create a new canvas and open it.
   - *Expected (stage 36):* the **whole page** is on screen, centred, with a small margin
     around it — not just its top half.
   Pinch out (two fingers) until some space around the page is visible.
   - *Expected:* the white page with its edge and shadow (stage 32), and around it a
     **faint ring of eight pages** — left, right, above, below and the four corners — drawn
     as pale paper with **dashed** edges. Beyond the ring is the plain surround.
   - *Expected screenshot A:* one page surrounded by eight dashed ghost pages.

## 2. Write into each side ring and one corner

2. With **Pen**, start a short word in the **left** ghost page and lift the pen.
   - *Expected:* while writing, the ink shows normally. On pen-up the ink **stays**, and
     the left ghost page becomes a **real page** (white, solid edge, inside the shadow).
     A new ghost ring appears around the wider grid.
3. Repeat in the **right**, **top** and **bottom** ghost pages.
   - *Expected:* each becomes a real page on pen-up; the grid is always a rectangle (e.g.
     writing left and then below adds the corner page too).
4. Start a stroke in one **corner** ghost page (e.g. top-left of the grid).
   - *Expected:* the grid grows by one column and one row at once.
5. Write a long stroke that **starts on a page and runs off across two ghost-ring widths**.
   - *Expected:* the ink shows the whole way while writing, and on pen-up every page it
     crossed is added (multi-page jump); nothing it drew disappears.
   - *Expected screenshot B:* a multi-page grid with ink on the new pages.

   Operator result (fill in):
   - [ ] left / right / top / bottom pages appear: __________
   - [ ] corner page appears: __________
   - [ ] long stroke adds every page it crossed, no ink vanishes on pen-up: __________

## 3. Write off the ring

6. Pan so there is surround visible **beyond** the ghost ring, and put the pen down there
   (outside any page and outside the ring) and draw.
   - *Expected:* **nothing happens** — no ink, no stroke, and the canvas does not pan.
7. Switch to **Eraser** and rub over ink on a page that was added in step 2.
   - *Expected:* the eraser removes it; the page stays (erase and Undo never remove pages).
8. Tap **Undo** after a stroke that added a page.
   - *Expected:* the stroke goes; the page it added stays.

   Operator result (fill in):
   - [ ] off-ring stroke does nothing: __________
   - [ ] eraser works on new pages; Undo / erase keep the pages: __________

## 4. Reach the cap

9. Keep writing into the right-hand ghost page until the grid is **8 pages wide**.
   - *Expected:* once the grid is 8 pages wide, no ghost pages are drawn to its left or
     right (the ring stays above and below).
10. Start a stroke on the right-most page and continue it past the grid's right edge.
    - *Expected:* the ink is **cut off at the grid edge while you write** (the part past the
      edge is never shown), the stroke is kept, and a one-line notice appears at the top of
      the canvas: **"Canvas is at its 8-page limit this way"**. It disappears by itself
      after a few seconds and does not block writing.
    - *Expected screenshot C:* the notice over an 8-page-wide grid.

    Operator result (fill in):
    - [ ] no ring beyond the cap: __________
    - [ ] live ink clipped at the cap line, notice shown: __________

## 5. Reopen

11. Press **Back** to the Library.
    - *Expected:* the canvas thumbnail shows the whole grown grid.
12. Reopen the canvas (and also force-stop the app and reopen it).
    - *Expected:* the grid is exactly as you left it — every added page, the same ring.

    Operator result (fill in):
    - [ ] thumbnail shows the grid: __________
    - [ ] grid kept after reopen and after a restart: __________

## 6. Low-latency pen on and off

13. Run sections 2–4 with Settings → Ink → **Low-latency pen ON** (the default), then turn
    it **OFF**, reopen the canvas and run sections 2–4 again.
    - *Expected:* identical behaviour both ways: the ring, growth on pen-up, nothing drawn
      off the ring, the clipped live ink at the cap. With it on, a stroke that adds a page
      shows no flicker or gap at pen-up (the wet ink hands off to the new page's dry ink).

    Operator result (fill in):
    - [ ] low-latency ON: __________
    - [ ] low-latency OFF: __________

## 7. Agent jobs send the visible region (stage 35)

14. Create a new canvas. On page 1 write **"2 + 2 ="**. Write into the **right** ghost page
    so the grid is two pages wide, and on that second page write a different question,
    e.g. **"capital of France?"**.
15. Pan and zoom so **only the second page** fills the screen (page 1 off screen). Tap
    **Send** (Ask).
    - *Expected:* the answer is about **the second page's question** (Paris), not "2 + 2";
      the agent's markup (a highlight, or the answer text) lands **on the second page's
      writing**, not on page 1 and not offset by a page.
    - *Expected screenshot D:* the second page with the agent markup on its writing.
16. Now write into the **left** ghost ring of page 1 so the grid grows left (three pages
    wide). Pan back to the second page.
    - *Expected:* the markup from step 15 has **not moved** — it is still on the writing.
17. Tap the answer card.
    - *Expected:* the pulse lights up the marked writing on the second page (the anchor
      follows the job's region).

    Operator result (fill in):
    - [ ] Ask about page 2 answers about page 2: __________
    - [ ] markup lands on page 2's writing: __________
    - [ ] markup unchanged after growing left: __________

## 8. Too far out: "Zoom in to send"

18. Pinch out until all three pages are on screen side by side.
    - *Expected:* **Send is greyed out** and a red **"Zoom in to send"** hint shows beside
      it; the Note sheet's job-type choices and its Send are disabled too, with the same
      hint.
19. Zoom back in until at most two pages are on screen.
    - *Expected:* Send is enabled again and the hint disappears.
20. Pan so no page is on screen at all (only the surround).
    - *Expected:* Send is disabled with **"Scroll to a page to send"**.

    Operator result (fill in):
    - [ ] Send disabled + "Zoom in to send" when zoomed far out: __________
    - [ ] re-enabled after zooming in: __________

## 9. Formalize from a region

21. Draw a small box-and-arrow sketch on the **second page**. With only the second page on
    screen, open the Note sheet, pick **Formalize** and send.
    - *Expected:* a new canvas "<title> — formalized" opens **fully visible** (fitted to the
      screen, stage 36); the clean diagram matches the
      sketch's layout and fills the new canvas the same way the sketch filled the screen
      region (the new canvas is the size of the exported region, not stretched or shifted).
    - *Expected screenshot E:* the formalized canvas beside the sketch.

    Operator result (fill in):
    - [ ] formalized canvas matches the region: __________

## 10. Server

22. After the server deploy, run the deploy canary (`inkwell canary` on the staging host,
    as in the release runbook).
    - *Expected:* the canary passes (it sends a single-page export with no origin, which
      is valid unchanged).

    Operator result (fill in):
    - [ ] canary passes: __________

## 11. Fit to screen (stage 36)

23. Open an existing single-page note that has writing near the **bottom** of the page.
    - *Expected:* the whole page is visible at once, centred, with a small margin; nothing
      is cut off at the bottom.
24. Without panning or zooming, tap **Send** (Ask).
    - *Expected:* the answer is about **the whole page**, including the writing at the
      bottom (before stage 36 a fresh note sent only its top half on this tablet).
25. Open the multi-page canvas from section 7 (or any canvas with 2+ pages).
    - *Expected:* **all its pages** are on screen at once. (A grid 3+ pages long on one axis
      may show "Zoom in to send" once fitted — that is the stage 35 floor, expected.)
26. On a fitted single-page note, write a stroke from inside the page out into the ghost
    ring next to it (the margin or the space beside the page).
    - *Expected:* the page is added on pen-up and the view **does not jump** — the page you
      were writing on stays exactly where it was on screen.
27. Pan and zoom somewhere else, then tap **Fit** (pinned at the right end of the toolbar,
    beside ⋮).
    - *Expected:* the whole grid is back on screen, centred. Fit is visible without
      scrolling the toolbar in both portrait and landscape.
28. Rotate the tablet (or resize the window) while zoomed in on part of a page.
    - *Expected:* the view is not re-fitted by the rotation; tap **Fit** to fit it.
29. Repeat step 27 with Settings → Ink → **Low-latency pen** on (the default) and write
    right after tapping Fit.
    - *Expected:* the ink appears under the pen tip and stays exactly where it was written
      after pen-up (no offset, no jump).

    Operator result (fill in):
    - [ ] a note opens with the whole page visible: __________
    - [ ] Ask on a fresh note answers about the whole page: __________
    - [ ] a multi-page canvas opens with all pages visible: __________
    - [ ] writing into the ring grows the page without the view jumping: __________
    - [ ] Fit brings the whole grid back: __________
    - [ ] low-latency ink after Fit lands under the pen: __________

## Kill switch (EXPANDABLE_CANVAS = false)

30. On a build with `EXPANDABLE_CANVAS` flipped to `false`, open any canvas.
    - *Expected:* no ghost ring; a stroke started outside the page does not start; a
      stroke written from the page across its edge is cut off at the edge while you write
      and after pen-up (the fixed page, as before stage 34). Ask still sends what is on
      screen, clipped to the page.
