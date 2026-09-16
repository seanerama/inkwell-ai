# Stage 15: Space settings on device: rename, colour, prompt, add a space

- **Type:** feature
- **Depends on:** 14
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/33
- **Design:** SPEC §1.1 ("adding a new agent = adding a space"), §4.1 (user-editable spaces), §9.3; contract `device-api` (`POST /spaces`, `PATCH /spaces/{id}` per stage 13's additive section); ADR-0010

## Objectives

Make the agent editable from the tablet. After this stage a long-press on a tab (or
its ⋯ menu) opens **Space settings**: name, colour, model and the system prompt, saved
to the server and mirrored back. A "+" at the end of the tab bar creates a new space
with a name, which is a new agent by definition. No offline editing; no delete.

## What to build

**Space settings sheet (`ui/SpaceSettingsSheet.kt`, `ui/SpaceSettingsViewModel.kt`)**
- Fields: Name (single line), Colour (the existing pen palette plus the four seed
  colours, shown as swatches), Model (dropdown: `claude-sonnet-5`, `claude-opus-5`,
  `claude-haiku-4-5-20251001`, plus "Custom…" free text), System prompt (multiline,
  monospace off, soft keyboard, 8000-char counter). "Move left" / "Move right"
  buttons adjust `position` by swapping with the neighbour (two `PATCH` calls).
- Save → `PATCH /spaces/{id}` with only the changed fields → on 200 upsert the
  returned row and call `SpaceSync.refresh()`. Errors: 403 `disabled` → "Editing
  spaces is turned off on the server"; 422 → the server's message inline; network →
  "Offline — changes not saved" with the sheet kept open.
- The prompt field has a one-line helper: "How this space's agent should think and
  answer. It runs on the server before every job in this space."

**New space (`ui/SpaceTabBar.kt`)**
- A trailing "+" tab → dialog with Name (required) and Colour → `POST /spaces` →
  refresh → the new tab is selected with an empty Library. Slug is derived by the
  server. 409 → "A space with that name already exists".

**Retrofit (`net/DeviceApi.kt`, `net/DeviceRepository.kt`, `net/Models.kt`)**
- `createSpace(SpaceCreateRequest)`, `patchSpace(id, SpacePatchRequest)`;
  `explicitNulls=false` so unset fields are omitted (partial PATCH).

**Kill-switch** `BuildConfig.SPACE_SETTINGS` (OFF = no ⋯ menu, no "+" tab; the tab
bar from stage 14 is read-only). Same documented release-ON exception.

## Interface contracts

- **Exposes:** on-device space editing and creation.
- **Consumes:** `device-api` `POST /spaces`, `PATCH /spaces/{id}` (stage 13);
  `SpaceSync` (stage 14); ADR-0010.

## Testing requirements

- Unit: the PATCH body contains only changed fields; position swap computes the two
  patches; error mapping for 403/409/422/network.
- Instrumented (MockWebServer): edit the prompt → recorded PATCH body → mirror shows
  the new prompt; create a space → new tab present and selected.
- Compose UI test: sheet opens from the tab menu, Save disabled until a field
  changes.
- Smoke asset `smoke/space-settings.md` (tablet): open Learning's settings, append
  "Always answer in French." to the prompt, save, Ask on a Learning canvas → French
  answer. Then "+" → "Cooking" (orange) → new tab, new canvas, Ask works with the
  default preamble. Results table.

## Acceptance conditions

- [ ] Kill-switch `BuildConfig.SPACE_SETTINGS` (documented release-ON exception)
- [ ] UI-smoke asset `smoke/space-settings.md` authored
- [ ] Additive only: no schema change on either side
- [ ] A prompt edit on the tablet changes the next answer in that space (smoke)
- [ ] Existing suite stays green; CI all-green

## Pipeline test: NO
