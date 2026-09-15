"""The Postgres-backed job queue (ADR-0003).

The ``jobs`` table *is* the queue. Workers claim with ``FOR UPDATE SKIP LOCKED``, run
the handler outside the lock, and write the result in a final transaction. A job left
``running`` past ``lease_expires_at`` is requeued once and failed with an ``error``
card on the second lease expiry.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta

from sqlalchemy import select, text, tuple_
from sqlalchemy.orm import Session

from app.config import get_settings
from app.db.models import Card, Job
from app.jobs.cursor import decode_cursor, encode_cursor
from app.jobs.handlers import get_handler
from app.logging import get_logger

log = get_logger("jobs")
NOTIFY_CHANNEL = "inkwell_jobs"
MAX_ATTEMPTS = 2


def _now() -> datetime:
    return datetime.now(UTC)


def _transition(job: Job, to_status: str) -> None:
    log.info("job.transition", job_id=str(job.id), **{"from": job.status, "to": to_status})
    job.status = to_status


def enqueue(
    session: Session,
    *,
    space_id: uuid.UUID,
    job_type: str,
    direction: str = "to_agent",
    canvas_id: uuid.UUID | None = None,
    request: dict | None = None,
) -> Job:
    job = Job(
        space_id=space_id,
        canvas_id=canvas_id,
        direction=direction,
        type=job_type,
        status="queued",
        request=request or {},
    )
    session.add(job)
    session.commit()
    session.refresh(job)
    log.info("job.transition", job_id=str(job.id), **{"from": "none", "to": "queued"})
    try:
        session.execute(text(f"NOTIFY {NOTIFY_CHANNEL}"))
        session.commit()
    except Exception:  # NOTIFY is a best-effort wake; polling covers the miss.
        session.rollback()
    return job


def claim_one(session: Session, worker_id: str) -> Job | None:
    """Claim the oldest queued job atomically, mark it running, and return it."""
    settings = get_settings()
    job = session.execute(
        select(Job)
        .where(Job.status == "queued")
        .order_by(Job.created_at)
        .with_for_update(skip_locked=True)
        .limit(1)
    ).scalar_one_or_none()
    if job is None:
        return None
    now = _now()
    _transition(job, "running")
    job.locked_at = now
    job.locked_by = worker_id
    job.lease_expires_at = now + timedelta(seconds=settings.lease_seconds)
    job.attempts += 1
    session.commit()
    session.refresh(job)
    return job


def _clear_lock(job: Job) -> None:
    job.locked_at = None
    job.locked_by = None
    job.lease_expires_at = None


def sweep_expired_leases(session: Session) -> int:
    """Requeue or fail jobs whose lease has expired. Returns the number acted on."""
    now = _now()
    expired = (
        session.execute(
            select(Job)
            .where(Job.status == "running")
            .where(Job.lease_expires_at.is_not(None))
            .where(Job.lease_expires_at < now)
            .with_for_update(skip_locked=True)
        )
        .scalars()
        .all()
    )
    acted = 0
    for job in expired:
        if job.attempts >= MAX_ATTEMPTS:
            _transition(job, "failed")
            job.error = "lease expired"
            _clear_lock(job)
            session.add(
                Card(
                    job_id=job.id,
                    kind="error",
                    title="Job failed",
                    body="The job did not complete within its lease and was abandoned.",
                    anchors=[],
                    actions=[],
                )
            )
        else:
            _transition(job, "queued")
            _clear_lock(job)
        acted += 1
    session.commit()
    return acted


def process_one(session: Session, worker_id: str) -> bool:
    """Claim and run a single job. Returns True if a job was processed."""
    job = claim_one(session, worker_id)
    if job is None:
        return False

    if job.cancel_requested:
        _transition(job, "cancelled")
        _clear_lock(job)
        session.commit()
        return True

    handler = get_handler(job.type)
    if handler is None:
        _transition(job, "failed")
        job.error = f"no handler for job type {job.type}"
        _clear_lock(job)
        session.add(
            Card(
                job_id=job.id,
                kind="error",
                title="Job failed",
                body=f"No handler is registered for `{job.type}`.",
                anchors=[],
                actions=[],
            )
        )
        session.commit()
        return True

    try:
        result = handler(job.request)
    except Exception as exc:  # handler failure -> failed + error card, never silent
        _transition(job, "failed")
        job.error = str(exc)
        _clear_lock(job)
        session.add(
            Card(
                job_id=job.id,
                kind="error",
                title="Job failed",
                body=f"The handler raised an error: {exc}",
                anchors=[],
                actions=[],
            )
        )
        session.commit()
        return True

    session.refresh(job)
    if job.cancel_requested:
        _transition(job, "cancelled")
    else:
        job.result = result
        _transition(job, "done")
    _clear_lock(job)
    session.commit()
    return True


def sync_page(session: Session, cursor: str | None, limit: int = 100) -> tuple[list[Job], str]:
    """Return jobs after ``cursor`` in ``(updated_at, id)`` ascending order.

    Without a cursor, the most recent ``limit`` jobs (both directions) are returned in
    ascending order. The returned cursor marks the last job in the page; when a page is
    empty the incoming cursor is echoed unchanged.
    """
    decoded = decode_cursor(cursor) if cursor else None
    if decoded is None:
        rows = list(
            session.execute(select(Job).order_by(Job.updated_at.desc(), Job.id.desc()).limit(limit))
            .scalars()
            .all()
        )
        rows.reverse()
    else:
        ts, job_id = decoded
        rows = list(
            session.execute(
                select(Job)
                .where(tuple_(Job.updated_at, Job.id) > tuple_(ts, job_id))
                .order_by(Job.updated_at, Job.id)
                .limit(limit)
            )
            .scalars()
            .all()
        )
    if rows:
        last = rows[-1]
        next_cursor = encode_cursor(last.updated_at, last.id)
    else:
        next_cursor = cursor or ""
    return rows, next_cursor
