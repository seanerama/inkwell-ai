# Stage 14: Space tabs: server-mirrored spaces, per-space send and accent, move canvas between spaces

- **Type:** feature
- **Depends on:** 13
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/32
- **Design:** SPEC §2, §4.1, §6.3 (accent), §9.3 (TabBar › CanvasGrid), §12 Phase 3; contracts `ink-storage` (v3, unchanged), `device-api` (`GET /spaces`, `space_id` on `POST /jobs`); ADR-0010

## Objectives

This is the Phase 3 acceptance from the tablet: the same note sent from **Work** and
from **Learning** produces recognisably different responses. After this stage the
Library sits under a horizontally scrollable tab bar of the server's spaces, each tab
has its own folder tree and Trash, new canvases land in the active space, a canvas or
folder can be moved to another space, every job is posted with the canvas's own
space, and agent marks are drawn in the space's accent colour. The device stops
inventing space ids: it mirrors the server's (ADR-0010).

## What to build

**Mirror + reconciliation (`data/SpaceSync.kt`, `data/CanvasRepository.kt`)**
- `SpaceSync.refresh()`: `GET /spaces` → upsert every row into Room by server id
  (name, slug, prompt, tools, model, color, position). Then reconcile: for each local
  space whose id is not in the server list but whose slug matches a server space,
  rewrite `canvases.space_id` and `folders.space_id` from the local id to the server
  id and delete the local row, all in one Room transaction. Local spaces with no slug
  match are left alone (there should be none; log a warning). Idempotent.
- Triggers: app start when a token is stored, pull-to-refresh on the Library, and
  after the send loop's first successful `/sync` of a session. Failure (offline,
  401) is non-fatal: the cached mirror is used and the tab bar shows a subtle
  "spaces not synced" hint only when there is no cached server space at all.
- `ensureDefaultSpace` remains for the unpaired first launch only (placeholder,
  slug `work`).
- **No Room migration.** `ink-storage` stays at v3; the reconciliation is a data
  rewrite. Extend the migration/instrumented tests with: seed placeholder + canvas
  with strokes → mirror with a server `work` id → canvas and folders carry the
  server id, stroke count unchanged, placeholder gone; running it twice is a no-op.

**Tab bar (`ui/SpaceTabBar.kt`, `ui/LibraryScreen.kt`, `ui/LibraryViewModel.kt`)**
- Above the Library breadcrumb (SPEC §9.3): one tab per space ordered by `position`,
  label = name, selected indicator in the space `color`. Horizontally scrollable.
  Active space id persisted in prefs; defaults to the first tab. Switching tabs
  resets the breadcrumb to that space's root.
- Library content, "+" (New canvas / New folder) and Trash are all scoped to the
  active space (the repository is already per-space; only the id source changes).

**Move between spaces (`data/LibraryRepository.kt`, Move… dialog)**
- The Move… dialog gains a second section "Other spaces" listing the other tabs.
  Moving a canvas to another space sets `space_id` and `folder_id = null` (space
  root). Moving a folder moves its whole subtree (folders and canvases, including
  trashed ones) to the target space root. Cards and layers are untouched.
- Agent-origin canvases may now differ from the server's `canvases.space_id`;
  accepted per ADR-0010 (Phase 4 reconciles).

**Send (`ui/CanvasViewModel.kt`, `net/DeviceRepository.kt`)**
- `POST /jobs` carries the **canvas's** `space_id`. If that id is a placeholder (no
  server row with that id after a refresh attempt), block the send with
  "Spaces not synced yet — check the connection and try again." Delete the
  `workSpaceId`/slug lookup path when the flag is on (keep it only under
  `SPACES = false`).
- `JobRequestBuilder` unchanged otherwise. Formalize output already carries the
  server `space_id`, which now matches the local one.

**Accent (`ui/CanvasViewModel.kt`, `render/AnnotationRenderer.kt`)**
- `accentColor` = the canvas's space `color` (parse via the existing
  `AnnotationRenderer` helper; fallback `DEFAULT_ACCENT`). Agent layers on user
  canvases render 70 % in that colour (SPEC §6.3); the stage 12 opaque rule for
  `origin=agent` canvases is unchanged.

**Kill-switch** `BuildConfig.SPACES` (debug and release; OFF = today's single
seeded space, no tab bar, slug lookup on send). Document the release-ON exception the
same way as `LIBRARY`.

## Interface contracts

- **Exposes:** server-id-keyed `spaces` mirror; `SpaceSync.refresh()`; per-space
  Library; move-between-spaces; accent from space colour.
- **Consumes:** `device-api` `GET /spaces` and `POST /jobs.space_id` (unchanged);
  `ink-storage` v3 (unchanged); stage 13 prompts (for the acceptance); ADR-0010.

## Testing requirements

- Unit (Robolectric/JVM): reconciliation rewrites and deletes as specified, is
  idempotent, leaves unmatched local spaces; move-canvas clears `folder_id`;
  move-folder carries the subtree; accent parse.
- Instrumented: mirror round-trip through MockWebServer `GET /spaces` with four
  spaces → four tabs; strokes intact after reconciliation; send posts the canvas's
  `space_id` (assert the recorded request body).
- Compose UI test: tab switch changes the Library content and resets the breadcrumb.
- Smoke asset `smoke/spaces.md` (the Phase 3 acceptance, on the tablet):
  1. Install; the tab bar shows Work, Home, Learning, Business; the existing canvases
     are all under Work with their ink.
  2. Write a short note (e.g. "why does the sky look blue?") on a Work canvas; Ask.
  3. Long-press the canvas → Move… → Learning; open it from the Learning tab; Ask
     again.
  4. Pass = the two answers are recognisably different in voice and shape (Work
     terse/action-oriented; Learning explanatory with a check question) and the
     Learning marks are drawn in the Learning accent. Results table with both
     summaries pasted.

## Acceptance conditions

- [ ] Kill-switch `BuildConfig.SPACES` (documented release-ON exception; OFF restores single-space behaviour)
- [ ] UI-smoke asset `smoke/spaces.md` authored (Work vs Learning on the same note)
- [ ] Additive only: no Room schema change; reconciliation is transactional and idempotent, ink survives (tested)
- [ ] Jobs are posted with the canvas's own `space_id` (tested)
- [ ] Existing suite stays green; CI all-green

## Pipeline test: NO
