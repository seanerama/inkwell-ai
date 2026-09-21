"""Stage 26: baseline brain recall — retrieve_brain ordering, caps, gating (ADR-0013 §3).

Entries are inserted directly with explicit ``created_at`` so recency ordering is
deterministic (rows in one transaction share ``now()``). Runs against the real Postgres
so the generated ``search`` tsvector column exists for the full-text branch.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta

from app.brain.retrieve import retrieve_brain
from app.brain.store import compute_hash
from app.db.models import BrainEntry

_T0 = datetime(2026, 10, 1, 12, 0, 0, tzinfo=UTC)


def _mk(db, *, text, kind="fact", tags=None, space="work", canvas_id=None, at=_T0):
    entry = BrainEntry(
        space_slug=space,
        kind=kind,
        text=text,
        tags=tags or [],
        source_canvas_id=canvas_id,
        hash=compute_hash(text),
        created_at=at,
    )
    db.add(entry)
    db.commit()
    db.refresh(entry)
    return entry


def test_empty_store_returns_empty_string(db):
    assert retrieve_brain(db, "work", None, "anything") == ""


def test_ordering_same_canvas_then_instruction_then_recent(db):
    canvas = uuid.uuid4()
    _mk(db, text="Alpha fact about the mango tree", canvas_id=canvas, at=_T0)
    _mk(db, text="Budget offsite in Austin", at=_T0 + timedelta(hours=1))
    _mk(db, text="Gamma unrelated recent note", at=_T0 + timedelta(hours=2))

    body = retrieve_brain(db, "work", canvas, "offsite")
    lines = body.splitlines()
    assert len(lines) == 3
    # (a) same-canvas first, (b) instruction match, (c) newest remaining.
    assert lines[0].startswith("[fact] Alpha fact about the mango tree")
    assert lines[1].startswith("[fact] Budget offsite in Austin")
    assert lines[2].startswith("[fact] Gamma unrelated recent note")


def test_line_format_has_kind_tags_date_and_short_id(db):
    entry = _mk(db, text="Renewal is due", kind="task", tags=["Ops", "renewal"], at=_T0)
    line = retrieve_brain(db, "work", None, None)
    short = str(entry.id).replace("-", "")[:8]
    assert line == f"[task] Renewal is due — #Ops #renewal — 2026-10-01 (id:{short})"


def test_cap_by_count_at_twelve(db):
    for i in range(20):
        _mk(db, text=f"short note number {i}", at=_T0 + timedelta(minutes=i))
    lines = retrieve_brain(db, "work", None, None).splitlines()
    assert len(lines) == 12


def test_cap_by_chars_stops_before_twelve(db):
    big = "lorem ipsum dolor sit amet " * 40  # ~1080 chars -> ~270 tokens per line
    for i in range(12):
        _mk(db, text=f"{i} {big}", at=_T0 + timedelta(minutes=i))
    lines = retrieve_brain(db, "work", None, None).splitlines()
    assert 0 < len(lines) < 12  # the ~1500-token cap bites before 12 entries
    est_tokens = sum(max(1, len(line) // 4) for line in lines)
    assert est_tokens <= 1500


def test_only_same_space_and_live_entries(db):
    _mk(db, text="home only entry", space="home", at=_T0)
    deleted = _mk(db, text="work deleted entry", at=_T0 + timedelta(minutes=1))
    deleted.deleted_at = datetime.now(UTC)
    db.commit()
    _mk(db, text="work live entry", at=_T0 + timedelta(minutes=2))

    body = retrieve_brain(db, "work", None, None)
    assert "work live entry" in body
    assert "home only entry" not in body
    assert "work deleted entry" not in body
