# Stage 28: Fix: Settings/pairing is unreachable from the Library and clipped off the canvas toolbar

- **Type:** bug
- **Depends on:** 24
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/58
- **Design:** SPEC §9.3; `ui/LibraryScreen.kt`, `ui/CanvasScreen.kt` (`Toolbar`), `ui/MainActivity.kt` (`showSettings`)

## Objectives

The owner could not find where to enter the server URL and token on v0.0.17. The
only entry is a text button at the far end of a non-scrolling canvas toolbar, and the
Library, the launch screen since stage 11, has none. After this stage Settings is one
tap from the Library and never clipped on a canvas.

## What to build

- Library top bar: a gear `IconButton` (contentDescription "Settings", testTag
  `LibraryTags.SETTINGS`) next to the Trash entry → `onOpenSettings` (already
  plumbed through `LibraryRoute`).
- Canvas toolbar: wrap the `Row` in `horizontalScroll(rememberScrollState())` or
  move Undo + Settings into a trailing overflow (⋮) menu; either way the Settings
  node must be displayed in portrait and landscape and on a pushed-document canvas.
- Settings screen: show the app version and the paired server's `/health` version
  (the pairing "Check" already does this) so upgrade confusion is visible.

## Interface contracts

- **Exposes:** nothing new. **Consumes:** existing `onOpenSettings` plumbing.

## Testing requirements

- Compose UI tests: Library shows the gear and it opens the settings/pairing
  screen; canvas toolbar Settings node `assertIsDisplayed()` in portrait width
  (~800 dp) and landscape (~1280 dp) with `SEND_ENABLED` on.

## Acceptance conditions

- [ ] Regression test in place (Settings reachable from Library; toolbar Settings displayed at both widths)
- [ ] `smoke/android-pairing.md` gains a line: "Settings is reachable from the Library gear"
- [ ] Existing suite stays green; CI all-green

## Pipeline test: NO
