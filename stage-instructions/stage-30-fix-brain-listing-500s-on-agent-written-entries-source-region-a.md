# Stage 30: Fix: brain listing 500s on agent-written entries (source_region array vs Rect) and brain_search AND-matching returns nothing for multi-word queries

- **Type:** bug
- **Depends on:** 27
- **Work-item:** https://github.com/seanerama/inkwell-ai/issues/65
- **Design:** contracts `agent-output` (`Rect` = `[x, y, w, h]`), `device-api` (`BrainEntry` shape = SPEC §4); ADR-0013 §1; `server/app/api/schemas.py` (`BrainEntryOut`), `server/app/brain/store.py` (`search_entries`), `server/app/agent/tools.py`

## Objectives

Found shipping v0.0.18. (1) `GET /brain/{slug}` returns `500` for any result that
contains an entry written by the model, because `BrainEntryOut.source_region` is typed
as an object while the persisted value is the contract's four-number array. The stage
27 Brain view therefore fails the moment Remember has been used. (2) `brain_search`
ANDs every query term (`websearch_to_tsquery`), so the model's natural four-word query
found nothing that a one-word query finds. After this stage listings never 500 and a
multi-word lookup degrades to OR-matching instead of an empty result.

## What to build

- `BrainEntryOut.source_region: list[float] | None` (exactly 4 floats, normalised
  0–1), accepting a legacy object `{x,y,w,h}` on read via a validator and emitting the
  array. Update the dated `device-api` section to state the array form (it already
  says "SPEC §4 shapes"; make it explicit). No migration: stored JSONB is already the
  array.
- `search_entries(session, slug, q, limit)`: run the AND query first; if it returns
  zero rows and `q` has more than one term, rerun with OR semantics
  (`to_tsquery('english', terms joined by ' | ')` built from `plainto_tsquery` tokens)
  ranked by `ts_rank_cd`; both the route and the tool call the same function.
  `result.brain_lookups[]` gains `"mode": "and" | "or"`.
- Tests: route listing with an agent-written entry (fixture `brain_writes` with a
  region) returns 200 and the array; legacy object input serialises; AND-then-OR
  fallback on `"Q3 offsite date location"` against the entry "Q3 offsite: Austin, 14
  Oct" returns it in OR mode; one-word queries unchanged.
- `smoke/brain-recall.md`: add a row for `brain_lookups.count ≥ 1` on the four-word
  query.

## Interface contracts

- **Exposes:** array `source_region` on `BrainEntry`; OR fallback; `brain_lookups.mode`.
- **Consumes:** `agent-output` `Rect` (unchanged), `device-api` brain routes (shape
  clarified, additive).

## Testing requirements

As above; plus the stage 27 instrumented Brain view test must use an entry with a
region so the device parser is proven against the array form.

## Acceptance conditions

- [ ] Regression tests in place (listing with agent-written entry; OR fallback)
- [ ] Contract note clarified, no route or schema break
- [ ] Existing suite stays green; CI all-green

## Pipeline test: NO
