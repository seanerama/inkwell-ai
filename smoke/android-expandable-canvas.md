# UI-smoke: Android expandable canvas (stage 34, ADR-0014 §2)

Manual "observably-works" check for the **Operator**, run on the real tablet (Lenovo Idea
Tab Pro, Android 14) with the active stylus. It covers the ghost ring, writing into it to
add pages in any direction, the 8-page cap, and the grid surviving a reopen. Browser smoke
does not apply to a native client (ADR-0001), so this human pass is the replacement.

## Preconditions

- A **debug** APK is installed (`assembleDebug`, or a debug build from the Release page).
  The feature is behind `BuildConfig.EXPANDABLE_CANVAS`: **ON in debug, OFF in release**
  until stage 35 (region export) turns it on. On a release APK there is no ring and the
  canvas is the fixed page — that is the expected OFF behaviour, not a failure of this smoke.
- The Library is on (default): you can create a new canvas from the Library.
- Settings → Ink → **Low-latency pen** is noted; the whole smoke runs twice, once with it
  **on** (the default) and once **off** (see the last section).

## 1. The ghost ring

1. In the Library create a new canvas and open it. Pinch out (two fingers) until the whole
   page and some space around it are visible.
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

## Release APK (switch OFF)

14. On a **release** APK open any canvas.
    - *Expected:* no ghost ring; a stroke started outside the page does not start; a
      stroke written from the page across its edge is cut off at the edge while you write
      and after pen-up (the fixed page, as before stage 34).
