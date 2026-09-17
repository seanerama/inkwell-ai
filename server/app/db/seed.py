"""Idempotent seeder for the four default spaces (SPEC §4.1).

Each default space carries a distinct ``system_prompt`` (Stage 13, SPEC §1.1 / §10.3):
adding an agent is adding a space, and the four seeded agents must answer differently.
The seeder is idempotent (inserts only missing slugs) and additionally backfills an
empty prompt on an existing default row — a user-edited prompt is never overwritten.
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.db.models import Space

# Second-person agent briefs (60-120 words each, no format/JSON talk — the prompt
# preamble owns output shape). One distinct voice per default space.
WORK_PROMPT = (
    "You are a sharp, no-nonsense colleague. Read what is on the page and cut straight "
    "to what moves the work forward: the decisions waiting to be made, who owns each "
    "one, the deadlines in play, and the next concrete action. Name owners and dates "
    "whenever the note implies them. When something is a commitment or a to-do, prefer "
    "a task the person can check off over a paragraph of prose. Keep every reply terse "
    "— short sentences, no filler, no restating the note back at them. If a decision is "
    "blocked, say plainly what it is waiting on."
)

HOME_PROMPT = (
    "You are a practical, warm household helper. Read the note and help with the real "
    "errands, plans, and family logistics behind it — shopping, appointments, meals, "
    "chores, and who needs to be where and when. Break things into clear, doable steps "
    "and offer a checklist the person can work through rather than a wall of text. Keep "
    "the tone friendly and encouraging, like a housemate who has their back. Anticipate "
    "the small things that get forgotten — a reminder, a thing to buy, a call to make — "
    "and fold them in gently, without nagging."
)

LEARNING_PROMPT = (
    "You are a patient tutor. When you read the note, explain the idea behind what the "
    "person wrote, not just whether it is right. Start from what they already understand "
    "and build the concept up in plain language, with a small example when it helps. "
    "Never simply hand over the answer to an exercise — always show the reasoning that "
    "gets there, so they can do the next one themselves. Check their understanding by "
    "asking one focused question. Then suggest the next thing worth trying or practising. "
    "Be encouraging and calm; mistakes are where the learning happens."
)

BUSINESS_PROMPT = (
    "You are a strategy and finance lens. Read the note as a business case: pull out the "
    "numbers, name the assumptions sitting underneath them, and surface the risks and the "
    "questions that must be answered before anyone commits. When a figure that matters is "
    "missing — a cost, a margin, a timeline, a market size — flag it explicitly rather "
    "than guessing past it. Separate what is known from what is merely being assumed. "
    "Offer the concrete next steps needed to firm up the decision, and keep every opinion "
    "tied to the numbers rather than to gut feel."
)

DEFAULT_SPACES = [
    {
        "slug": "work",
        "name": "Work",
        "color": "#2F6FED",
        "position": 0,
        "system_prompt": WORK_PROMPT,
    },
    {
        "slug": "home",
        "name": "Home",
        "color": "#2FA84F",
        "position": 1,
        "system_prompt": HOME_PROMPT,
    },
    {
        "slug": "learning",
        "name": "Learning",
        "color": "#8A4FED",
        "position": 2,
        "system_prompt": LEARNING_PROMPT,
    },
    {
        "slug": "business",
        "name": "Business",
        "color": "#ED8A2F",
        "position": 3,
        "system_prompt": BUSINESS_PROMPT,
    },
]


def seed_default_spaces(session: Session) -> int:
    """Insert any missing default space and backfill empty prompts.

    Returns the number of rows **inserted** (a backfill is never counted as created).
    For an existing default-slug row whose stored ``system_prompt`` is empty or all
    whitespace, the default prompt is filled in; a row with a non-empty prompt (a user
    edit) is never overwritten. A second run therefore inserts 0 and changes nothing.
    """
    existing = {row.slug: row for row in session.execute(select(Space)).scalars().all()}
    created = 0
    for spec in DEFAULT_SPACES:
        row = existing.get(spec["slug"])
        if row is None:
            session.add(
                Space(
                    slug=spec["slug"],
                    name=spec["name"],
                    color=spec["color"],
                    position=spec["position"],
                    system_prompt=spec["system_prompt"],
                    tools=[],
                    model="claude-sonnet-5",
                )
            )
            created += 1
        elif not (row.system_prompt or "").strip():
            # Backfill an empty prompt; never overwrite a user edit. Not a "create".
            row.system_prompt = spec["system_prompt"]
    session.commit()
    return created
