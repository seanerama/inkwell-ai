# UI-smoke: Space settings — rename, colour, model, prompt, and add a space (Stage 15)

Manual "observably-works" check for the **Handoff Tester** (and the Operator), run on the
real tablet (Lenovo Idea Tab Pro, Android 14) with the active stylus. This is the **Stage 15
acceptance**: *a space is editable from the tablet — long-press a tab (or its menu) opens
**Space settings** (name, colour, model, system prompt), Save writes only the changed fields
to the server (`PATCH /spaces/{id}`) and mirrors them back; a trailing **"+"** tab creates a
new space (a new agent) via `POST /spaces`; and **a prompt edit changes the next answer in
that space*** (SPEC §1.1, §4.1, §9.3; ADR-0010; contract `device-api` §Stage 13 additions).
Browser smoke does not apply to a native client (ADR-0001), so this human pass is the
replacement. Run it on every release APK that changes space editing, the settings sheet, the
"+" tab, or the create/patch path.

## Kill-switch — which build to test

On-device space editing is gated by `BuildConfig.SPACE_SETTINGS`, **ON in both debug and
release** by documented exception (Stage-15 spec: prod is not promoted, staging is the only
environment, and the server routes are independently gated by `SPACES_EDITABLE` — see
Preconditions). With `SPACE_SETTINGS` **OFF** the Stage-14 tab bar is **read-only**: no
long-press/⋯ menu on a tab and **no "+" tab** (spaces still mirror + switch exactly as in
`smoke/spaces.md`). Flip `SPACE_SETTINGS` to `false` in `app/build.gradle.kts` (both build
types) to fall back without a code change. `SPACE_SETTINGS` is independent of `SPACES` (the
tab bar itself), `LIBRARY`, `ONE_TAP_ASK`, `CARD_ACTIONS`, and `FORMALIZE`.

## Preconditions

- The first release APK that includes Stage 15 (or later) is installed. `INK_ENABLED`,
  `LIBRARY`, `SPACES`, and `SPACE_SETTINGS` are ON, so the app opens on the **Library** under
  the space tabs, with a per-tab menu and a trailing **"+"**.
- **Pairing is set in Settings** (base URL + token). Editing mirrors the server, so a valid
  pairing is required.
- **The server has `SPACES_EDITABLE=true`** (stage 13, default OFF). Confirm with the
  Operator before the run — **without it every Save/Create returns `403` and the sheet shows
  *"Editing spaces is turned off on the server"*** (that is the expected OFF behaviour, not a
  bug; record it and stop).
- The server has the four Phase-3 spaces (**Work, Home, Learning, Business**) via
  `GET /spaces`. If it does not, the run is blocked (record it).
- A canvas exists (or can be created) under **Learning** for the prompt-effect check.

## Scenario 1 — edit Learning's prompt; the next answer changes (the acceptance)

1. **Open Learning's settings.** On the **Learning** tab, long-press the tab (or use its ⋯
   menu) → **Space settings**.
   - *Expected:* a sheet with **Name** (Learning), a **Colour** swatch row (the pen palette +
     the four seed colours, Learning's current colour ringed), a **Model** dropdown, and the
     **System prompt** field with the helper line *"How this space's agent should think and
     answer. It runs on the server before every job in this space."* and a live **/ 8000**
     counter. **Save** is **disabled** (nothing changed yet).
   - *Expected screenshot A:* the Space settings sheet for Learning.
2. **Append to the prompt.** In the System prompt, append **"Always answer in French."** The
   counter updates and **Save** becomes **enabled**. Tap **Save**.
   - *Expected:* the sheet closes; the Learning tab is still selected. (Under the hood only
     `system_prompt` is PATCHed; the name/colour/model are untouched.)
3. **Ask on a Learning canvas.** Open (or create) a canvas under **Learning**, write a short
   note (e.g. **"why is the sky blue?"**) and tap **Send** (Ask).
   - *Expected:* the answer comes back **in French** — proof the prompt edit reached the
     server and shaped the next job in this space.
   - *Expected screenshot B:* the French answer on a Learning canvas.

## Scenario 2 — add a new space "Cooking"; it becomes the active tab

1. **Create a space.** Tap the trailing **"+"** tab → the **New space** dialog. Enter name
   **"Cooking"** and pick the **orange** swatch (`#ED8A2F`). Tap **Create**.
   - *Expected:* the dialog closes; a new **Cooking** tab appears (slug derived by the server)
     and **becomes the selected tab** with an **empty Library** (its own folder tree + Trash).
   - *Expected screenshot C:* the Cooking tab selected, empty Library.
2. **Ask on a Cooking canvas.** Tap **+** → **New canvas** (it opens under Cooking), write a
   note and **Send** (Ask).
   - *Expected:* a normal answer using the **default preamble** (Cooking has no custom prompt
     yet); marks in Cooking's (orange) accent.
   - *Expected screenshot D:* a Cooking answer with the orange accent.
3. **Duplicate-name guard (optional).** Tap **"+"** again and try **"Cooking"** once more.
   - *Expected:* the dialog stays open with *"A space with that name already exists"* (409).

## Scenario 3 — rename, colour, model, reorder, persistence

1. **Rename + recolour + model.** Open **Cooking**'s settings. Change **Name** to
   **"Recipes"**, pick a different swatch, and choose a different **Model** from the dropdown
   (or **Custom…** and type one). Tap **Save**.
   - *Expected:* the tab relabels to **Recipes**, its accent changes; only the fields you
     changed are sent (partial PATCH). Save is disabled again until you change something.
2. **Move left / right.** Open a tab's settings and tap **Move right** (then **Move left**).
   - *Expected:* the tab swaps position with its neighbour (two PATCH calls); the tab order in
     the bar updates. The end tabs disable the button that would move them off the ends.
3. **Kill + reopen.** Swipe the app from recents and relaunch.
   - *Expected:* the edited names/colours, the new **Recipes** space, the tab order, and all
     ink persist (mirrored from the server on the app-start refresh).

## Offline behaviour

- With Wi-Fi **off**, open a settings sheet, change a field, and **Save**.
  - *Expected:* the sheet **stays open** with *"Offline — changes not saved"*; nothing is
    written (no offline editing — Stage-15 scope). Turn Wi-Fi back on and Save again to
    confirm it then succeeds.

## Pass / fail criteria

- [ ] Long-press a tab (or its menu) opens **Space settings** with Name / Colour / Model /
      System prompt (+ helper line + / 8000 counter); **Save is disabled until a field changes**.
- [ ] Save sends **only the changed fields** and mirrors the row back (tab relabels/recolours).
- [ ] **Editing Learning's prompt changes the next answer in Learning** (French answer).
- [ ] The **"+"** tab creates a new space that **becomes the active tab** with an empty Library;
      Ask works there with the default preamble.
- [ ] A duplicate name on create shows **"A space with that name already exists"** (409).
- [ ] **Move left/right** reorders the tab (position swap); edits + order persist after relaunch.
- [ ] With `SPACES_EDITABLE` **off** on the server, Save/Create show *"Editing spaces is turned
      off on the server"* (403) and change nothing.
- [ ] Offline Save keeps the sheet open with *"Offline — changes not saved"*; no write occurs.

## Record the run

- Tag / build under test: __________
- Scenario 1 (prompt edit → French answer) (PASS/FAIL): __________
- Scenario 2 (add Cooking; selected; default answer; 409 guard) (PASS/FAIL): __________
- Scenario 3 (rename/colour/model; move; persistence) (PASS/FAIL): __________
- Offline behaviour (PASS/FAIL): __________
- **Learning "before" vs "after" answer (paste):** __________
- Notes / anomalies: __________

## Failure signals

- **No ⋯/long-press menu, no "+" tab:** `SPACE_SETTINGS` is OFF in this build (read-only
  Stage-14 bar — note the build), or the device is unpaired / `GET /spaces` failed.
- **Every Save/Create returns "Editing spaces is turned off on the server":** the server has
  `SPACES_EDITABLE=false` (stage 13 default). Ask the Operator to enable it on staging.
- **Prompt edit doesn't change the answer:** the PATCH did not land (check the recorded
  `space_id`/prompt with the Operator), or the job was posted with the wrong `space_id`.
- **"slug is immutable" (422):** a slug was sent on a PATCH — a client bug (the patch model
  must never include `slug`); capture logs.
- **New tab not selected / empty Library missing:** the post-create refresh did not re-select
  the created space; capture logs.

## Results log

| Date (UTC) | Release | Operator | S1 | S2 | S3 | Offline | Prompt edit changed answer? | Notes |
|---|---|---|---|---|---|---|---|---|
| 2026-09-17 02:20 | v0.0.13 | seanerama | pass | pass | pass | n/a | yes | owner: edited the system prompt and the Ask answer reflected it; '+' new space worked on device (stage 16 failure is emulator/test-side) |
