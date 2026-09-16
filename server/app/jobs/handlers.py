"""Worker job-handler registry.

Two flavours of handler share this registry:

- **Simple handlers** ``(request: dict) -> result: dict`` — for self-contained internal
  jobs such as ``system.ping``. The queue writes the returned dict to ``jobs.result`` and
  transitions the job to ``done``; an exception becomes ``failed`` + a generic error card.

- **Job handlers** ``(ctx: JobContext) -> result: dict`` — for jobs that need the DB
  session, the job row, and the blob store (Stage 5's ``canvas.annotate``). They may set
  columns on ``ctx.job`` (token accounting) and add ``cards`` rows to ``ctx.session``; the
  queue commits everything together. To fail with a *specific* single error card they
  raise ``JobFailure(title, body)``; any other exception becomes ``failed`` + a generic
  error card.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:  # avoid import cycles / heavy imports at module load
    from sqlalchemy.orm import Session

    from app.blobs.base import BlobStore
    from app.db.models import Job

# A simple handler takes the job's ``request`` dict and returns the ``result`` dict.
Handler = Callable[[dict], dict]

_HANDLERS: dict[str, Handler] = {}


@dataclass
class JobContext:
    """Everything a rich job handler needs to run and record a job."""

    session: Session
    job: Job
    request: dict
    blob_store: BlobStore


# A job handler takes the full context and returns the ``result`` dict.
JobHandler = Callable[[JobContext], dict]

_JOB_HANDLERS: dict[str, JobHandler] = {}


class JobFailure(Exception):
    """Raised by a job handler to fail its job with exactly one error card."""

    def __init__(self, message: str, *, title: str = "Job failed", body: str | None = None) -> None:
        super().__init__(message)
        self.title = title
        self.body = body or message


def register_handler(job_type: str, fn: Handler) -> None:
    _HANDLERS[job_type] = fn


def get_handler(job_type: str) -> Handler | None:
    return _HANDLERS.get(job_type)


def register_job_handler(job_type: str, fn: JobHandler) -> None:
    _JOB_HANDLERS[job_type] = fn


def get_job_handler(job_type: str) -> JobHandler | None:
    return _JOB_HANDLERS.get(job_type)


def registered_types() -> list[str]:
    return sorted(set(_HANDLERS) | set(_JOB_HANDLERS))


def _ping(request: dict) -> dict:
    return {"pong": True}


register_handler("system.ping", _ping)

# Register the canvas.annotate / canvas.ask job handler (import for its side effect).
from app.jobs import canvas_annotate  # noqa: E402,F401
