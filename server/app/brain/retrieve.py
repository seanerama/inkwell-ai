"""Baseline brain recall for the ``<brain_context>`` block (Stage 26, ADR-0013 §3).

``retrieve_brain`` fills the delimited brain-context block that ``build_system_prompt``
already emits (empty until this stage). It gathers a small, most-relevant slice of the
space's brain in a fixed order, dedupes by id, and caps it so the block never dominates
the prompt:

  (a) live entries written from THIS canvas (``source_canvas_id``), newest first;
  (b) full-text matches for the job's instruction (ranked, when an instruction is given);
  (c) the newest entries in the space.

Each entry renders on one line as ``[kind] text — #tag1 #tag2 — YYYY-MM-DD (id:<short>)``.
The whole thing is retrieved *reference material* (data), which the prompt preamble
already labels as never-instructions; the call site gates it on ``BRAIN_ENABLED`` (off →
empty string → today's empty block).
"""

from __future__ import annotations

import uuid

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.brain.store import collapse_ws, search_entries
from app.db.models import BrainEntry

# Caps (ADR-0013 §3): at most 12 entries, and roughly 1,500 tokens estimated as chars/4.
_MAX_ENTRIES = 12
_MAX_TOKENS = 1500
_CHARS_PER_TOKEN = 4


def format_entry(entry: BrainEntry) -> str:
    """One brain entry as a single prompt line (shared with the ``brain_search`` tool)."""
    text = collapse_ws(entry.text or "")
    parts = [f"[{entry.kind}] {text}"]
    tags = " ".join(f"#{t}" for t in (entry.tags or []) if t)
    if tags:
        parts.append(tags)
    date = entry.created_at.strftime("%Y-%m-%d") if entry.created_at else "0000-00-00"
    parts.append(date)
    short = str(entry.id).replace("-", "")[:8]
    return " — ".join(parts) + f" (id:{short})"


def _same_canvas(session: Session, space_slug: str, canvas_id: uuid.UUID) -> list[BrainEntry]:
    return list(
        session.execute(
            select(BrainEntry)
            .where(BrainEntry.space_slug == space_slug)
            .where(BrainEntry.source_canvas_id == canvas_id)
            .where(BrainEntry.deleted_at.is_(None))
            .order_by(BrainEntry.created_at.desc())
            .limit(_MAX_ENTRIES)
        )
        .scalars()
        .all()
    )


def retrieve_brain(
    session: Session,
    space_slug: str,
    canvas_id: uuid.UUID | None,
    instruction: str | None,
) -> str:
    """Return the brain-context body (may be empty) for one job. Gated by BRAIN_ENABLED
    at the call site: this function itself assumes the brain is enabled."""
    seen: set[uuid.UUID] = set()
    ordered: list[BrainEntry] = []

    def _add(entries: list[BrainEntry]) -> None:
        for entry in entries:
            if entry.id in seen:
                continue
            seen.add(entry.id)
            ordered.append(entry)

    # (a) entries written from this very canvas.
    if canvas_id is not None:
        _add(_same_canvas(session, space_slug, canvas_id))
    # (b) ranked full-text matches for the instruction.
    instr = (instruction or "").strip()
    if instr:
        _add(search_entries(session, space_slug, instr, limit=_MAX_ENTRIES))
    # (c) newest entries in the space.
    _add(search_entries(session, space_slug, None, limit=_MAX_ENTRIES))

    lines: list[str] = []
    tokens = 0
    for entry in ordered:
        if len(lines) >= _MAX_ENTRIES:
            break
        line = format_entry(entry)
        cost = max(1, len(line) // _CHARS_PER_TOKEN)
        if lines and tokens + cost > _MAX_TOKENS:
            break
        lines.append(line)
        tokens += cost

    return "\n".join(lines)
