"""Stage 13: default per-space prompts + idempotent backfill (SPEC §1.1 / §10.3).

``conftest._clean`` already seeds the four spaces (with their prompts) before every
test, so these tests manipulate the seeded rows directly to exercise fresh-insert and
backfill paths.
"""

from __future__ import annotations

from sqlalchemy import select, text

from app.db.models import Space
from app.db.seed import DEFAULT_SPACES, seed_default_spaces


def _default_prompt(slug: str) -> str:
    return next(s["system_prompt"] for s in DEFAULT_SPACES if s["slug"] == slug)


def test_fresh_db_seeds_four_distinct_nonempty_prompts(db):
    db.execute(text("DELETE FROM spaces"))
    db.commit()

    created = seed_default_spaces(db)
    assert created == 4

    rows = db.execute(select(Space)).scalars().all()
    prompts = [r.system_prompt for r in rows]
    assert len(prompts) == 4
    assert all(p.strip() for p in prompts), "every default prompt must be non-empty"
    assert len(set(prompts)) == 4, "the four default prompts must be pairwise distinct"


def test_second_run_creates_zero_and_changes_nothing(db):
    db.execute(text("DELETE FROM spaces"))
    db.commit()
    assert seed_default_spaces(db) == 4

    before = {r.slug: r.system_prompt for r in db.execute(select(Space)).scalars().all()}
    assert seed_default_spaces(db) == 0
    after = {r.slug: r.system_prompt for r in db.execute(select(Space)).scalars().all()}
    assert before == after


def test_backfill_fills_empty_and_preserves_user_edit(db):
    work = db.execute(select(Space).where(Space.slug == "work")).scalar_one()
    home = db.execute(select(Space).where(Space.slug == "home")).scalar_one()

    # work: a user edit that must be preserved. home: force-emptied → must be backfilled.
    work.system_prompt = "MY OWN CUSTOM WORK PROMPT"
    home.system_prompt = "   "  # whitespace counts as empty
    db.commit()

    created = seed_default_spaces(db)
    assert created == 0  # backfill is never counted as a create

    db.refresh(work)
    db.refresh(home)
    assert work.system_prompt == "MY OWN CUSTOM WORK PROMPT", "a user edit is never overwritten"
    assert home.system_prompt == _default_prompt("home"), "an empty prompt is backfilled"
