"""Lease expiry: requeue once, then fail with an error card on the second expiry."""

import uuid
from datetime import UTC, datetime, timedelta

from app.db.base import get_sessionmaker
from app.db.models import Card, Job
from app.jobs.queue import claim_one, enqueue, sweep_expired_leases


def _expire(session, job_id: uuid.UUID) -> None:
    job = session.get(Job, job_id)
    job.lease_expires_at = datetime.now(UTC) - timedelta(seconds=1)
    session.commit()


def test_lease_requeue_then_fail_with_error_card(db):
    sm = get_sessionmaker()
    with sm() as session:
        space_id = _first_space_id(session)
        job = enqueue(session, space_id=space_id, job_type="system.ping")
        job_id = job.id

    # First claim -> running (attempts=1); expire the lease.
    with sm() as session:
        claim_one(session, "w1")
    with sm() as session:
        _expire(session, job_id)
    with sm() as session:
        acted = sweep_expired_leases(session)
        assert acted == 1
        assert session.get(Job, job_id).status == "queued"  # requeued once

    # Second claim -> running (attempts=2); expire again -> failed + error card.
    with sm() as session:
        claim_one(session, "w2")
    with sm() as session:
        _expire(session, job_id)
    with sm() as session:
        sweep_expired_leases(session)
        job = session.get(Job, job_id)
        assert job.status == "failed"
        assert job.error
        cards = session.query(Card).filter(Card.job_id == job_id).all()
        assert len(cards) == 1
        assert cards[0].kind == "error"


def _first_space_id(session):
    from sqlalchemy import select

    from app.db.models import Space

    return session.execute(select(Space).order_by(Space.position)).scalars().first().id
