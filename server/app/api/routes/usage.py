"""Usage route: running token totals (SPEC §10.5, contract device-api ``GET /usage``).

Returns ``{ jobs, input_tokens, output_tokens, by_space: { <space_id>: {...} } }`` summed
from the ``jobs`` table over jobs that recorded token usage (agent jobs). ``since`` is an
optional RFC 3339 timestamp filtering on ``created_at``.
"""

from __future__ import annotations

from datetime import datetime

from fastapi import APIRouter, Depends
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.api.deps import get_db, require_token
from app.api.errors import ApiError
from app.db.models import Job

router = APIRouter()


def _parse_since(since: str | None) -> datetime | None:
    if since is None:
        return None
    try:
        return datetime.fromisoformat(since.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ApiError(422, "validation", "since must be an RFC 3339 timestamp") from exc


@router.get("/usage")
def usage(
    since: str | None = None,
    db: Session = Depends(get_db),
    _=Depends(require_token),
) -> dict:
    since_dt = _parse_since(since)

    stmt = select(
        Job.space_id,
        func.count(Job.id),
        func.coalesce(func.sum(Job.input_tokens), 0),
        func.coalesce(func.sum(Job.output_tokens), 0),
    ).where(Job.input_tokens.is_not(None))
    if since_dt is not None:
        stmt = stmt.where(Job.created_at >= since_dt)
    stmt = stmt.group_by(Job.space_id)

    total_jobs = 0
    total_in = 0
    total_out = 0
    by_space: dict[str, dict[str, int]] = {}
    for space_id, jobs, in_tokens, out_tokens in db.execute(stmt).all():
        jobs = int(jobs)
        in_tokens = int(in_tokens)
        out_tokens = int(out_tokens)
        total_jobs += jobs
        total_in += in_tokens
        total_out += out_tokens
        by_space[str(space_id)] = {
            "jobs": jobs,
            "input_tokens": in_tokens,
            "output_tokens": out_tokens,
        }

    return {
        "jobs": total_jobs,
        "input_tokens": total_in,
        "output_tokens": total_out,
        "by_space": by_space,
    }
