# Stage 11: Library: many canvases, folders, titles; open, create, move, delete

- **Type:** feature
- **Depends on:** 10
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/27
- **Design:** SPEC §4.2, §9.3 (CanvasGrid), §13 Q3; contract `ink-storage` (Room, additive); ADR-0001

## Objectives

The owner asked to "save canvases in some sort of file structure". Ink already
autosaves on every pen-up; what is missing is having more than one canvas and a place
to keep them. After this stage the app opens on a **Library**: folders and canvases
for the current space, with create, rename, move, delete (to a Trash that empties
after 30 days), and open. The canvas screen gets a title and a back button. Formalize
(stage 12) needs this to have somewhere to put its output.

Design note (SPEC §2): a *space* is an agent context, not a folder. Folders live
**inside** a space, one tree per space, so the file structure never leaks across
agent contexts. Depth is unlimited in the model but the UI shows breadcrumbs.

## What to build

**Data (`data/`, Room v2 → v3, additive migration)**
- `FolderEntity(id, space_id, parent_id?, name, created_at, updated_at, deleted_at?)`.
- `CanvasEntity` gains `folder_id?` (null = space root) and `deleted_at?` (Trash).
- `MIGRATION_2_3`: create `folders`, add the two nullable columns; existing canvases
  stay at the root, untouched. Migration test extends `InkDatabaseMigrationTest`.
- Repository: list folder contents (folders first, then canvases by `updated_at`
  desc), create canvas (default A4, one `user/ink` layer, title "Untitled N"),
  create/rename/move/delete folder (delete moves contents to Trash), soft-delete and
  restore canvas, purge Trash entries older than 30 days on app start.
- Canvas thumbnails: a 256-px PNG rendered on close via the existing exporter path,
  cached under `filesDir/thumbs/<canvas_id>.png`, regenerated when `updated_at`
  changes. Never the source of truth (SPEC invariant 1: ink is vectors).

**UI (`ui/`)**
- `LibraryScreen` replaces the direct-to-canvas launch: breadcrumb bar (Space ›
  Folder › …), a grid of folder tiles and canvas tiles (thumbnail + title + relative
  date), a "+" FAB with New canvas / New folder, long-press for Rename / Move… /
  Delete, a Trash entry at the root with Restore / Delete forever. Portrait and
  landscape.
- `CanvasScreen`: top bar with back (returns to the folder the canvas is in), an
  editable title (tap to rename), and the existing toolbar. **Give the note pencil a
  visible "Note" label** (owner could not find it, 2026-09-16).
- Settings keeps pairing; the space tab bar remains Phase 3 — this stage works in
  the single seeded space but the model and screens are per-space from the start.
- Kill-switch `BuildConfig.LIBRARY`: OFF = today's behaviour (open the first canvas).

**Server**
- No change. Canvases stay device-truth (SPEC §3) until Phase 4 pushes require
  mirroring. Job requests continue to carry `canvas_id` as an opaque id.

## Interface contracts

- **Exposes:** `FolderEntity`, the library repository API, thumbnails; the Library
  and titled canvas screens.
- **Consumes:** contract `ink-storage` (additive Room v3: new table + nullable
  columns; `points` untouched). No server contract involved.

## Testing requirements

- JVM: repository ordering; create canvas seeds one ink layer; move keeps strokes;
  delete → Trash → restore round-trip; purge after 30 days (clock injected); rename
  bumps `updated_at`; thumbnail path derivation.
- Instrumented: migration 2 → 3 keeps every stroke byte-for-byte and leaves existing
  canvases at the root; Library renders two folders and a canvas; open → back returns
  to the same folder; New canvas creates and opens it.
- UI-smoke `smoke/library.md`: make a folder "Network", move the topology canvas into
  it, rename it, create a second canvas, delete one to Trash and restore it, kill and
  reopen the app — structure intact, ink intact, thumbnails present.

## Acceptance conditions

- [ ] Kill-switch / dark-launch flag (default OFF) for this net-new feature:
      `BuildConfig.LIBRARY`, stage-7 documented exception (ON in both build types).
- [ ] UI-smoke "observably-works" check authored for any user-facing surface
      (`smoke/library.md`).
- [ ] Additive migration only (no destructive schema change): Room v3 adds a table and
      nullable columns; migration test proves ink survives.
- [ ] The "Note" label is visible next to the pencil on the canvas toolbar.
- [ ] Existing suite stays green; CI all-green

## Pipeline test: NO
