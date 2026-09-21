"""Persist validated ``brain_writes`` into ``brain_entries`` (Stage 25, ADR-0013 §2).

Every done ``to_agent`` job's validated ``brain_writes`` become brain entries with
provenance (``source_canvas_id`` from the job, ``source_region`` from the write,
``job_id``). Exact-text duplicates within a live space are skipped (dedupe on
``(space_slug, hash)`` where ``deleted_at IS NULL``, enforced both here and by the
partial unique index).

The normalisation helpers (``collapse_ws`` / ``normalise_text`` / ``compute_hash`` /
``normalise_tags``) are the single source of truth for the hash: migration 0006's
backfill imports them so an existing row's hash is byte-identical to a runtime hash.
"""

from __future__ import annotations

import hashlib
import re
import uuid

from sqlalchemy import func, literal_column, select
from sqlalchemy.orm import Session

from app.db.models import BrainEntry, Job, Space

MAX_TAGS = 10

_WS_RE = re.compile(r"\s+")


def collapse_ws(value: str) -> str:
    """Collapse every run of whitespace to a single space and strip the ends."""
    return _WS_RE.sub(" ", value).strip()


def normalise_text(value: str) -> str:
    """The canonical form hashed for dedupe: whitespace-collapsed, then lowercased."""
    return collapse_ws(value).lower()


def compute_hash(text: str) -> str:
    """sha256 of the normalised text. MUST match migration 0006's backfill exactly."""
    return hashlib.sha256(normalise_text(text).encode("utf-8")).hexdigest()


def normalise_tags(tags: list[str] | None) -> list[str]:
    """Lowercase, strip, drop empties, dedupe (order-preserving), cap at ``MAX_TAGS``."""
    out: list[str] = []
    for raw in tags or []:
        tag = raw.strip().lower()
        if tag and tag not in out:
            out.append(tag)
        if len(out) >= MAX_TAGS:
            break
    return out


def _live_entry(session: Session, space_slug: str, hash_: str) -> BrainEntry | None:
    return session.execute(
        select(BrainEntry)
        .where(BrainEntry.space_slug == space_slug)
        .where(BrainEntry.hash == hash_)
        .where(BrainEntry.deleted_at.is_(None))
    ).scalar_one_or_none()


def create_entry(
    session: Session,
    *,
    space_slug: str,
    kind: str,
    text: str,
    tags: list[str] | None = None,
    source_canvas_id: uuid.UUID | None = None,
    source_region: dict | None = None,
    job_id: uuid.UUID | None = None,
) -> tuple[BrainEntry, bool]:
    """Insert one brain entry, or return the existing live duplicate.

    Returns ``(entry, created)`` — ``created`` is False when a live entry with the same
    ``(space_slug, hash)`` already exists (idempotent create; used by the POST route and
    the ``save_to_brain`` card action). The row is flushed so its id is assigned.
    """
    hash_ = compute_hash(text)
    existing = _live_entry(session, space_slug, hash_)
    if existing is not None:
        return existing, False
    entry = BrainEntry(
        space_slug=space_slug,
        kind=kind,
        text=text,
        tags=normalise_tags(tags),
        source_canvas_id=source_canvas_id,
        source_region=source_region,
        job_id=job_id,
        hash=hash_,
    )
    session.add(entry)
    session.flush()
    return entry, True


def search_entries(
    session: Session, space_slug: str, query: str | None, *, limit: int = 10
) -> list[BrainEntry]:
    """Ranked full-text search over a space's live entries (Stage 25's route query).

    Mirrors ``GET /brain/{space_slug}`` exactly: ``websearch_to_tsquery('english', q)``
    ranked by ``ts_rank_cd`` with recency as the tiebreak. With no (or blank) ``query``,
    returns the newest live entries. Soft-deleted rows are never returned. This is the
    single search used by both baseline recall (``retrieve_brain``) and the
    ``brain_search`` tool, so the two rank identically to the device route.
    """
    stmt = (
        select(BrainEntry)
        .where(BrainEntry.space_slug == space_slug)
        .where(BrainEntry.deleted_at.is_(None))
    )
    q = (query or "").strip()
    if q:
        tsquery = func.websearch_to_tsquery("english", q)
        search = literal_column("search")
        stmt = (
            stmt.where(search.op("@@")(tsquery))
            .order_by(func.ts_rank_cd(search, tsquery).desc(), BrainEntry.created_at.desc())
            .limit(limit)
        )
    else:
        stmt = stmt.order_by(BrainEntry.created_at.desc()).limit(limit)
    return list(session.execute(stmt).scalars().all())


def persist_writes(session: Session, job: Job, writes: list[dict]) -> list[uuid.UUID]:
    """Persist a done job's validated ``brain_writes``; return the created entry ids.

    Runs inside the job's ``done`` transaction. A live-duplicate write is skipped (not
    counted in the returned ids); dedupe covers duplicates already in the DB *and*
    repeats within this same batch. Any error propagates so the job fails loudly.
    """
    space = session.get(Space, job.space_id)
    if space is None:  # pragma: no cover - a job always references a real space
        raise ValueError(f"job {job.id} references unknown space {job.space_id}")
    space_slug = space.slug

    created: list[uuid.UUID] = []
    seen: set[str] = set()
    for write in writes:
        text = write["text"]
        hash_ = compute_hash(text)
        if hash_ in seen:
            continue
        seen.add(hash_)
        entry, was_created = create_entry(
            session,
            space_slug=space_slug,
            kind=write["kind"],
            text=text,
            tags=write.get("tags"),
            source_canvas_id=job.canvas_id,
            source_region=write.get("source_region"),
            job_id=job.id,
        )
        if was_created:
            created.append(entry.id)
    return created
