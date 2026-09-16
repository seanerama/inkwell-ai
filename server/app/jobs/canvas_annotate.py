"""The ``canvas.annotate`` (Stage 5) and ``canvas.ask`` (Stage 7) job handler.

The handler is type-agnostic: it passes ``job.type`` through to ``run_agent``, which
selects the job guidance, the default instruction and the effort per type (SPEC
§10.2-10.5). It is registered once per implemented type below.

At run time it: honours the ``AGENT_ENABLED`` kill-switch, enforces the per-space daily
cap at claim, loads the exported PNG from the blob store, runs the agent with two-tier
validation and one retry, records ``input_tokens`` / ``output_tokens`` /
``coordinate_clamps`` on the job, writes ``jobs.result`` with a ``contract_version``
marker, and creates one ``cards`` row per returned card.

Any failure path is a normal ``failed`` transition with exactly one ``error`` card, so
the device observes it through ordinary sync (contract device-api).
"""

from __future__ import annotations

import base64
from datetime import UTC, datetime

from sqlalchemy import func, select

from app.agent.validate import AgentValidationError, run_agent
from app.config import get_settings
from app.db.models import Canvas, Card, Job, Space
from app.jobs.handlers import JobContext, JobFailure, register_job_handler
from app.logging import get_logger

log = get_logger("agent")

CONTRACT_VERSION = "agent-output/v1"

# Error-card wording per job type when the agent's response fails validation twice.
_FAILURE_TITLES = {
    "canvas.annotate": "Could not annotate",
    "canvas.ask": "Could not respond",
    "canvas.formalize": "Could not formalize",
}
_FAILURE_BODIES = {
    "canvas.annotate": "The agent could not produce a valid annotation for this canvas.",
    "canvas.ask": "The agent could not produce a valid response to this note.",
    "canvas.formalize": "The agent could not produce a clean diagram for this canvas.",
}

# Default canvas dimensions (SPEC §4.2) when the source canvas is not server-known.
_DEFAULT_WIDTH_CU = 2480
_DEFAULT_HEIGHT_CU = 3508


def _daily_count(ctx: JobContext) -> int:
    """to_agent jobs for this space since the start of the current UTC day, minus self."""
    day_start = datetime.now(UTC).replace(hour=0, minute=0, second=0, microsecond=0)
    return int(
        ctx.session.execute(
            select(func.count(Job.id))
            .where(Job.space_id == ctx.job.space_id)
            .where(Job.direction == "to_agent")
            .where(Job.created_at >= day_start)
            .where(Job.id != ctx.job.id)
            .where(Job.status != "cancelled")
        ).scalar_one()
    )


def _add_cards(ctx: JobContext, cards: list[dict]) -> None:
    for card in cards:
        ctx.session.add(
            Card(
                job_id=ctx.job.id,
                kind=card["kind"],
                title=card["title"],
                body=card.get("body", ""),
                anchors=card.get("anchors", []),
                actions=card.get("actions", []),
            )
        )


def _formalize_canvas(ctx: JobContext, result: dict) -> None:
    """Create the agent-origin ``canvases`` row for a done ``canvas.formalize`` job.

    Adds two server-added sibling keys to ``result`` next to ``contract_version`` — the
    created ``canvas`` and the ``source_canvas_id``. Neither is produced by the model
    (contract agent-output, same precedent as ``contract_version``). The row is added to
    ``ctx.session`` and flushed for its id; the queue commits it atomically with the job.
    """
    job = ctx.job
    width_cu = _DEFAULT_WIDTH_CU
    height_cu = _DEFAULT_HEIGHT_CU
    if job.canvas_id is not None:
        source = ctx.session.get(Canvas, job.canvas_id)
        if source is not None:
            width_cu = source.width_cu
            height_cu = source.height_cu

    meta_title = (job.request.get("meta") or {}).get("title")
    title = f"{meta_title} — formalized" if meta_title else "Formalized"

    canvas = Canvas(
        space_id=job.space_id,
        title=title,
        width_cu=width_cu,
        height_cu=height_cu,
        origin="agent",
    )
    ctx.session.add(canvas)
    ctx.session.flush()  # INSERT now (assign id) without ending the transaction
    ctx.session.refresh(canvas)  # load the server-default created_at

    result["canvas"] = {
        "id": str(canvas.id),
        "space_id": str(canvas.space_id),
        "title": canvas.title,
        "width_cu": canvas.width_cu,
        "height_cu": canvas.height_cu,
        "origin": canvas.origin,
        "created_at": canvas.created_at.isoformat() if canvas.created_at else None,
    }
    result["source_canvas_id"] = str(job.canvas_id) if job.canvas_id else None


def handle_canvas_annotate(ctx: JobContext) -> dict:
    settings = get_settings()
    job = ctx.job

    # Kill-switch (default OFF): fail immediately with the disabled error card.
    if not settings.agent_enabled:
        log.info("agent.disabled", job_id=str(job.id))
        raise JobFailure(
            "agent is disabled",
            title="Agent disabled",
            body="The AI agent is currently disabled on this server.",
        )

    # Per-space daily cap, enforced at claim (SPEC §10.5, contract device-api).
    if _daily_count(ctx) >= settings.agent_daily_cap:
        log.info("agent.daily_cap_exceeded", job_id=str(job.id), cap=settings.agent_daily_cap)
        raise JobFailure(
            "daily job cap exceeded",
            title="Daily limit reached",
            body=(
                "This space has reached its daily limit of "
                f"{settings.agent_daily_cap} agent jobs. Try again tomorrow."
            ),
        )

    image_key = ctx.request.get("image_key")
    if not image_key:
        raise JobFailure(
            f"no image for {job.type} job",
            title="Job failed",
            body="No exported image was attached to this job.",
        )
    try:
        image_bytes = ctx.blob_store.open(image_key)
    except KeyError as exc:
        raise JobFailure(
            f"image blob {image_key} not found",
            title="Job failed",
            body="The exported image could not be loaded.",
        ) from exc
    image_b64 = base64.standard_b64encode(image_bytes).decode("ascii")

    space = ctx.session.get(Space, job.space_id)
    model = space.model if space and space.model else ""
    space_prompt = space.system_prompt if space else ""

    try:
        run = run_agent(
            model=model,
            system_prompt=space_prompt,
            brain_context="",  # empty this stage (SPEC §10.3)
            image_b64=image_b64,
            instruction=ctx.request.get("instruction"),
            job_type=job.type,
            job_id=str(job.id),
        )
    except AgentValidationError as exc:
        # The agent WAS called; record the tokens it spent before failing the job.
        job.input_tokens = exc.input_tokens
        job.output_tokens = exc.output_tokens
        log.warning("agent.failed", job_id=str(job.id), error=str(exc).splitlines()[0])
        raise JobFailure(
            str(exc),
            title=_FAILURE_TITLES.get(job.type, "Could not annotate"),
            body=_FAILURE_BODIES.get(
                job.type, "The agent could not produce a valid annotation for this canvas."
            ),
        ) from exc

    job.input_tokens = run.input_tokens
    job.output_tokens = run.output_tokens
    job.coordinate_clamps = run.coordinate_clamps

    result = run.output.model_dump(by_alias=True)
    result["contract_version"] = CONTRACT_VERSION
    _add_cards(ctx, result["cards"])

    # canvas.formalize: the redraw becomes a new agent-origin canvas that rides back on
    # this same job (no Phase-4 push). Only for formalize; annotate/ask are unchanged.
    if job.type == "canvas.formalize":
        _formalize_canvas(ctx, result)

    log.info(
        "agent.done",
        job_id=str(job.id),
        input_tokens=run.input_tokens,
        output_tokens=run.output_tokens,
        coordinate_clamps=run.coordinate_clamps,
        annotations=len(result["annotations"]),
        cards=len(result["cards"]),
    )
    return result


register_job_handler("canvas.annotate", handle_canvas_annotate)
# Stage 7: canvas.ask reuses the same type-agnostic handler (job.type drives the prompt).
register_job_handler("canvas.ask", handle_canvas_annotate)
# Stage 12: canvas.formalize reuses the same handler; it additionally creates an
# agent-origin canvas row and adds the canvas/source_canvas_id sibling keys to result.
register_job_handler("canvas.formalize", handle_canvas_annotate)
