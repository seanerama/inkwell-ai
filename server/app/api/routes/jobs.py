"""Job routes (SPEC §8, contract device-api).

Stage 1 accepts the internal ``system.ping`` type; the SPEC ``to_agent``/``to_user``
types are recognised but return ``422 not_implemented`` until their stages land.
"""

from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, Request, Response
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.api.deps import get_db, require_token
from app.api.errors import ApiError
from app.api.schemas import JobCreate, JobOut, job_to_out
from app.db.models import DeviceToken, Job, Space
from app.jobs import queue

router = APIRouter()

# SPEC §7 job types that will land in later stages.
SPEC_JOB_TYPES = {
    "canvas.ask",
    "canvas.annotate",
    "canvas.formalize",
    "canvas.extract",
    "canvas.action",
    "agent.push_canvas",
    "agent.push_document",
    "agent.notify",
}
INTERNAL_JOB_TYPES = {"system.ping"}


def _resolve_space(db: Session, space_id: uuid.UUID | None) -> Space:
    if space_id is not None:
        space = db.get(Space, space_id)
        if space is None:
            raise ApiError(404, "not_found", "unknown space_id")
        return space
    space = db.execute(select(Space).order_by(Space.position).limit(1)).scalar_one_or_none()
    if space is None:
        raise ApiError(422, "validation", "no space available")
    return space


@router.post("/jobs", response_model=JobOut, status_code=202)
def create_job(
    body: JobCreate,
    request: Request,
    response: Response,
    db: Session = Depends(get_db),
    token: DeviceToken = Depends(require_token),
) -> JobOut:
    limiter = request.app.state.rate_limiter
    allowed, retry_after = limiter.check(str(token.id))
    if not allowed:
        raise ApiError(
            429,
            "rate_limited",
            "job submission rate limit exceeded",
            headers={"Retry-After": str(retry_after)},
        )

    if body.type in SPEC_JOB_TYPES:
        raise ApiError(422, "not_implemented", f"job type {body.type} is not implemented yet")
    if body.type not in INTERNAL_JOB_TYPES:
        raise ApiError(422, "validation", f"unknown job type {body.type}")

    space = _resolve_space(db, body.space_id)
    req: dict = {}
    if body.instruction is not None:
        req["instruction"] = body.instruction
    if body.selection is not None:
        req["selection"] = body.selection
    if body.export is not None:
        req["export"] = body.export

    job = queue.enqueue(
        db,
        space_id=space.id,
        job_type=body.type,
        direction="to_agent",
        canvas_id=body.canvas_id,
        request=req,
    )
    response.status_code = 202
    return job_to_out(job)


@router.get("/jobs/{job_id}", response_model=JobOut)
def get_job(
    job_id: uuid.UUID, db: Session = Depends(get_db), _: DeviceToken = Depends(require_token)
) -> JobOut:
    job = db.get(Job, job_id)
    if job is None:
        raise ApiError(404, "not_found", "unknown job id")
    return job_to_out(job)


@router.post("/jobs/{job_id}/cancel", response_model=JobOut)
def cancel_job(
    job_id: uuid.UUID, db: Session = Depends(get_db), _: DeviceToken = Depends(require_token)
) -> JobOut:
    job = db.get(Job, job_id)
    if job is None:
        raise ApiError(404, "not_found", "unknown job id")
    if job.status == "queued":
        queue._transition(job, "cancelled")
        db.commit()
    elif job.status == "running":
        job.cancel_requested = True
        db.commit()
    else:
        raise ApiError(409, "conflict", f"cannot cancel a job in state {job.status}")
    db.refresh(job)
    return job_to_out(job)
