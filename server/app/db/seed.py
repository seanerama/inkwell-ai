"""Idempotent seeder for the four default spaces (SPEC §4.1)."""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import Space

DEFAULT_SPACES = [
    {"slug": "work", "name": "Work", "color": "#2F6FED", "position": 0},
    {"slug": "home", "name": "Home", "color": "#2FA84F", "position": 1},
    {"slug": "learning", "name": "Learning", "color": "#8A4FED", "position": 2},
    {"slug": "business", "name": "Business", "color": "#ED8A2F", "position": 3},
]


def seed_default_spaces(session: Session) -> int:
    """Insert any missing default space (keyed by slug). Returns the number created."""
    existing = set(session.execute(select(Space.slug)).scalars().all())
    created = 0
    for spec in DEFAULT_SPACES:
        if spec["slug"] in existing:
            continue
        session.add(
            Space(
                slug=spec["slug"],
                name=spec["name"],
                color=spec["color"],
                position=spec["position"],
                system_prompt="",
                tools=[],
                model="claude-sonnet-5",
            )
        )
        created += 1
    session.commit()
    return created
