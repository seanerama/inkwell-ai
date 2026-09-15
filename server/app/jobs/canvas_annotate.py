"""The ``canvas.annotate`` job handler (SPEC §10.2-10.5, Stage 5).

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
from app.db.models import Card, Job, Space
from app.jobs.handlers import JobContext, JobFailure, register_job_handler
from app.logging import get_logger

log = get_logger("agent")

CONTRACT_VERSION = "agent-output/v1"


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
            "no image for canvas.annotate job",
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
            title="Could not annotate",
            body="The agent could not produce a valid annotation for this canvas.",
        ) from exc

    job.input_tokens = run.input_tokens
    job.output_tokens = run.output_tokens
    job.coordinate_clamps = run.coordinate_clamps

    result = run.output.model_dump(by_alias=True)
    result["contract_version"] = CONTRACT_VERSION
    _add_cards(ctx, result["cards"])

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
