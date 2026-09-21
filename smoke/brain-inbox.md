# UI-smoke: Brain on the device — Remember, recall, browse, prune (Stage 27)

Manual "observably-works" check for the **Handoff Tester**, run on the real tablet (Lenovo
Idea Tab Pro, Android 14) with the active stylus, against a freshly deployed **staging**
server. This is the **Phase 5 acceptance from the tablet**: *write a fact on a canvas, tap
**Remember**, and on a later, different canvas ask about it and get it back — then delete the
fact in the Brain view and confirm the answer no longer knows.* Plus the browsable view the
spec asks for: each space has a **Brain** you can search, prune and add to, and **Save to
brain** on a card works (SPEC §12 Phase 5, §4.7, §9.3; contract `device-api` `/brain/{slug}`;
ADR-0013 §6). Browser smoke does not apply to a native client (ADR-0001), so this human pass
is the replacement. Run it on every release APK that changes the Brain view, the Remember
option, or the save-to-brain card wiring.

## Kill-switch — which build to test

The device brain is gated by `BuildConfig.BRAIN`, **ON in both debug and release** by
documented exception (Stage-27 spec: prod is not promoted, staging is the only environment,
and the server brain routes are additionally gated by server-side `BRAIN_ENABLED`). With
`BRAIN` **OFF** there is no brain icon in the Library, no **Brain** item in a tab's ⋯ menu, no
**Remember** option in the Ask sheet, and `save_to_brain` card actions are hidden — the app
behaves exactly as before Stage 27. Flip `BRAIN` to `false` in `app/build.gradle.kts` (both
build types) to fall back without a code change. It is independent of
`LIBRARY`/`SPACES`/`FORMALIZE`.

## Preconditions

- The first release APK that includes Stage 27 (or later) is installed. `INK_ENABLED`,
  `LIBRARY`, `SPACES`, and `BRAIN` are ON, so the app opens on the **Library** under the space
  tabs and shows the brain (★) icon in the breadcrumb bar.
- **Pairing is set in Settings** (base URL + token) to the staging server, and the tabs
  populate (the device is online). The brain is **server-truth and online-only** (ADR-0013
  §6): with no connection the Brain view shows "Brain needs a connection" and greys the last
  list.
- **Server brain is enabled:** staging has **`BRAIN_ENABLED=true`** and **`AGENT_ENABLED=true`**
  (the model must actually run), and the stack was re-upped (`dc up -d api worker`). With
  `BRAIN_ENABLED` off the brain routes return `403 disabled`, the Brain view surfaces the
  connection error, and Remember writes nothing — that is the default-safe state, not this test.
- The server has the Phase-3 spaces (**Work, Home, Learning, Business**); this run uses **Work**.
- Two real model jobs run against the staging image, so this costs a little.

Throughout, `dc` is `docker compose -f deploy/compose.yml` in the environment directory on the
host (e.g. `/srv/inkwell/staging`), reached over Tailscale SSH (`ssh mini-hp01`); everything
else is on the tablet.

## Scenario 1 — Remember writes a fact, a later Ask recalls it

1. **Write the fact.** On the tablet, open the app on the Library, select the **Work** tab, and
   open (or create) a canvas. In ink, write: **`Q3 offsite: Austin, 14 Oct`**.
   - *Expected screenshot A:* the handwritten line on a Work canvas.
2. **Remember.** Tap **Note** → in the sheet, the job-type picker shows a fourth option
   **Remember** beside Ask / Mark up / Formalize. Tap **Remember**, then **Remember** (the send
   button label follows the picker).
   - *Expected:* a "Working…" indicator, then a side-panel **fact** card titled something like
     "Saved to brain" listing what was recorded (the offsite fact). Highlights, if any, render
     as usual.
   - *Expected screenshot B:* the "Saved to brain" card in the panel.
3. **Browse the Brain.** Go back to the Library and tap the **★ (Brain)** icon in the breadcrumb
   bar (or long-press the **Work** tab → **Brain**). The Brain view lists the entry newest-first
   with a **fact** kind chip, the text `Q3 offsite: Austin, 14 Oct`, and a date.
   - *Expected screenshot C:* the Brain view showing the offsite entry.
4. **Ask on a NEW canvas.** Back in the Library (still **Work**), create a **new** canvas and
   write: **`when is the offsite?`**. Tap **Note** → **Ask** → **Send**.
   - *Expected:* an **answer** card whose text says **Austin, 14 Oct** (recalled from the brain
     via the baseline `<brain_context>` injection, and/or a `brain_search` tool call when
     `AGENT_TOOLS_ENABLED` is on). The offsite fact was never on *this* canvas.
   - *Expected screenshot D:* the answer card naming Austin, 14 Oct.

## Scenario 2 — Delete in the Brain view, and the answer forgets

5. **Delete the fact.** Open the **Brain** view again. **Long-press** the offsite entry →
   **Delete**. The row disappears and a **Deleted / Undo** snackbar shows for ~5 s. Let it lapse
   (do **not** tap Undo).
   - *Expected screenshot E:* the Brain view with the entry gone (empty or only other entries).
6. **Ask again.** Create another **new** Work canvas and write **`when is the offsite?`** again.
   **Ask** → **Send**.
   - *Expected:* the answer **no longer knows** — it says it has no record of the offsite (no
     "Austin, 14 Oct"). Deleting the entry removed it from recall.
   - *Expected screenshot F:* the answer with no offsite date.

## Scenario 3 — Browse operations (search, add, save-to-brain, undo)

7. **Add manually.** In the Brain view tap **+** → pick a kind (e.g. **reference**), type a
   text and a tag, **Add**. The new entry appears at the top.
8. **Search.** Type a word from an entry into the search field; the list narrows to matches
   (a `GET …?q=…`); clearing it returns the newest list.
9. **Undo a delete.** Long-press an entry → **Delete**, then tap **Undo** within 5 s — the entry
   returns (re-`POST`ed).
10. **Save to brain from a card.** On a canvas, **Ask** something that yields a card with a
    **Save to brain** action; tap it. The card shows a **done/Saved** state and a **Saved to
    brain — View in Brain** snackbar; **View in Brain** opens the Brain view with the new entry.

## Results

Fill in on the run (PASS/FAIL + one line each). Attach screenshots A–F.

| Check | Result |
|---|---|
| Ink `Q3 offsite: Austin, 14 Oct` on a Work canvas | |
| **Remember** is the 4th picker option; sending shows a **"Saved to brain"** card | |
| Brain view (★ icon / tab ⋯ → Brain) lists the offsite entry (fact chip, text, date) | |
| New canvas → **Ask** "when is the offsite?" → answer says **Austin, 14 Oct** | |
| **Long-press → Delete** removes the row; the **Undo** snackbar shows for ~5 s | |
| After delete, a new **Ask** "when is the offsite?" **no longer knows** | |
| **+** adds a manual entry; it appears newest-first | |
| **Search** narrows the list (`?q=`); clearing restores newest | |
| **Undo** within 5 s restores the deleted entry | |
| A card's **Save to brain** works: card shows **Saved**; **View in Brain** opens the Brain | |
| Offline: Brain view shows **"Brain needs a connection"** and greys the last list | |
| Kill-switch OFF (`BRAIN=false`): no brain icon/menu, no Remember, no save-to-brain actions | |
| Screenshots A–F attached | |

## Notes

- The brain is **server-truth and never mirrored** (ADR-0013 §6): the Brain view fetches live
  from `GET /brain/{slug}` every time — there is no device Room table for it, and it is
  online-only. A network failure keeps the last-fetched list greyed under the connection banner.
- Recall is **keyword** (Postgres full-text search, ADR-0013 §3): the fact and the question
  share vocabulary ("offsite"), so the January-fact-in-March case works without embeddings.
- Delete is a **soft delete** on the server; the 5 s device **Undo** simply re-`POST`s the same
  entry (idempotent on normalised text), so it comes back with the same text.
- The brain icon uses a present `material-icons-core` glyph (★) with contentDescription
  "Brain"; the semantics ride on the label/description, not the exact glyph.
