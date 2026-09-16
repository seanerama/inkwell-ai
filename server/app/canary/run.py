"""``inkwell canary`` implementation (Stage 8).

A deploy with the agent enabled runs exactly ONE real ``canvas.ask`` job through the
already-running worker and the live Anthropic API against the committed fixture note,
and fails loudly unless it comes back ``done`` with at least one annotation AND one
card. The CLI only *creates* the job and *observes* it; the live worker container does
the processing (this module never runs the worker inline in the normal path).

The logic is split into small helpers so tests can drive it without a live worker:
``create_canary_job`` enqueues the job, ``poll_job`` waits for a terminal status from a
fresh session each iteration, ``report`` turns a finished job into an exit code + lines,
and ``run_canary`` composes them for the CLI.

Exit codes: ``0`` only on ``done`` with >=1 annotation and >=1 card; ``2`` on ``failed``
(or a ``done`` job missing annotations/cards); ``3`` on timeout.
"""

from __future__ import annotations

import sys
import time
import uuid
from collections.abc import Callable
from pathlib import Path

from sqlalchemy import select

from app.blobs import get_blob_store
from app.db.base import get_sessionmaker
from app.db.models import Job, Space
from app.jobs.queue import enqueue

# The committed fixture PNG (never the generator script — Pillow is not in the image).
FIXTURE_PNG = Path(__file__).parent / "fixture.png"

# Export metadata matching the default 2480x3508 canvas (contract coordinate-mapping).
CANARY_EXPORT = {"w": 1109, "h": 1568, "width_cu": 2480, "height_cu": 3508}

_TERMINAL = {"done", "failed", "cancelled"}


class CanaryError(Exception):
    """A setup failure before a job could be observed (e.g. the space is missing)."""


def create_canary_job(session, space_slug: str = "work") -> uuid.UUID:
    """Store the fixture in the blob store and enqueue a marked ``canvas.ask`` job.

    Raises ``CanaryError`` if the space slug does not resolve. The ``canary: True``
    marker on the request is what ``GET /usage`` excludes on.
    """
    space = session.execute(select(Space).where(Space.slug == space_slug)).scalar_one_or_none()
    if space is None:
        raise CanaryError(f"no space with slug {space_slug!r} — run `inkwell db seed`")

    data = FIXTURE_PNG.read_bytes()
    key = f"{uuid.uuid4().hex}.png"
    get_blob_store().put(key, data, "image/png")

    job = enqueue(
        session,
        space_id=space.id,
        job_type="canvas.ask",
        direction="to_agent",
        canvas_id=None,
        request={"image_key": key, "canary": True, "export": CANARY_EXPORT},
    )
    return job.id


def poll_job(
    session_factory: Callable,
    job_id: uuid.UUID,
    timeout: float,
    interval: float = 1.0,
) -> Job | None:
    """Poll ``Job.status`` from a fresh session until terminal or ``timeout`` elapses.

    Returns the detached terminal ``Job`` (its columns are all eagerly loaded), or
    ``None`` if the timeout is reached first. The interval is injectable so tests do not
    sleep.
    """
    deadline = time.monotonic() + timeout
    while True:
        with session_factory() as session:
            job = session.get(Job, job_id)
            if job is not None and job.status in _TERMINAL:
                session.expunge(job)
                return job
        if time.monotonic() >= deadline:
            return None
        time.sleep(interval)


def report(job: Job | None) -> tuple[int, list[str]]:
    """Turn a finished (or timed-out) job into an exit code and the lines to print.

    On success the FINAL line is exactly ``canary ok: done, <N> annotations, <M> cards``
    (no ``>>`` prefix — the deploy script adds that).
    """
    if job is None:
        return 3, ["canary timeout: the worker did not finish the job in time"]

    if job.status == "done":
        result = job.result or {}
        annotations = result.get("annotations", [])
        cards = result.get("cards", [])
        types = [a.get("type") for a in annotations]
        clamps = job.coordinate_clamps or 0
        n, m = len(annotations), len(cards)
        lines = [
            "status: done",
            f"summary: {result.get('summary', '')}",
            f"annotation types: {', '.join(types) if types else '(none)'}",
            f"coordinate_clamps: {clamps}",
            f"input_tokens: {job.input_tokens or 0}, output_tokens: {job.output_tokens or 0}",
        ]
        if n >= 1 and m >= 1:
            lines.append(f"canary ok: done, {n} annotations, {m} cards")
            return 0, lines
        lines.append(f"canary failed: done but {n} annotations and {m} cards (need >=1 of each)")
        return 2, lines

    if job.status == "failed":
        return 2, ["status: failed", f"error: {job.error or '(no error recorded)'}"]

    # cancelled or any other non-done terminal state.
    return 2, [f"status: {job.status}", f"canary failed: job ended {job.status}"]


def run_canary(space: str = "work", timeout: float = 90, interval: float = 1.0) -> int:
    """Compose the helpers for the CLI: create the job, wait, print, return exit code."""
    session_factory = get_sessionmaker()
    try:
        with session_factory() as session:
            job_id = create_canary_job(session, space)
    except CanaryError as exc:
        print(f"canary error: {exc}", file=sys.stderr)
        return 2

    job = poll_job(session_factory, job_id, timeout, interval)
    code, lines = report(job)
    for line in lines:
        print(line)
    return code
