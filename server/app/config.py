"""Runtime configuration, read from the environment (12-factor / ADR-0005)."""

from __future__ import annotations

from functools import lru_cache

from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="INKWELL_", extra="ignore")

    # Postgres connection URL (SQLAlchemy/psycopg form). The one required setting.
    database_url: str = "postgresql+psycopg://inkwell:inkwell@localhost:5432/inkwell"

    # ADR-0008: server-side pepper mixed into the token hash. Override in every env.
    token_pepper: str = "dev-pepper-change-me"

    # ADR-0008 dual-pepper grace window (Stage 18): the PREVIOUS pepper during a
    # rotation. When set (non-empty), verify_token falls back to it on a current-pepper
    # miss and transparently re-hashes the row to the current pepper. Unset (the default)
    # is single-pepper behaviour, i.e. exactly as before this stage.
    token_pepper_previous: str | None = None

    # ADR-0004: HMAC key for signed, expiring blob URLs.
    blob_signing_key: str = "dev-blob-key-change-me"

    # ADR-0004: LocalBlobStore root; keys are sharded by the first two characters.
    blob_dir: str = "/data/blobs"

    # ADR-0008: POST /jobs rate limit, submissions per token per minute.
    job_rate_limit_per_min: int = 30

    # Stage 23: push API rate limit, requests per agent token per minute.
    push_rate_limit_per_min: int = 10

    # ADR-0003: seconds a claimed job may stay `running` before the lease expires.
    lease_seconds: int = 120

    # Worker idle poll interval (seconds) between LISTEN/NOTIFY wakeups.
    worker_poll_seconds: float = 2.0

    # Stage 5 kill-switch (default OFF). When false, a canvas.annotate job fails
    # immediately with a single "agent disabled" error card. Accepts a bare
    # ``AGENT_ENABLED`` in addition to the ``INKWELL_``-prefixed form.
    agent_enabled: bool = Field(
        default=False,
        validation_alias=AliasChoices("AGENT_ENABLED", "INKWELL_AGENT_ENABLED"),
    )

    # SPEC §10.5 cost control: per-space daily job cap, enforced at claim time.
    agent_daily_cap: int = 200
    # ADR-0006 amendment (2026-09-16): structured outputs are OFF by default. The API
    # rejected the frozen schema three ways (minItems arity, additionalProperties,
    # "schema is too complex"); prompt-guided JSON + two-tier validation + one retry is
    # the default. ON sends the projected schema (app.agent.api_schema) as
    # output_config.format for when the API limits change.
    agent_structured_output: bool = Field(
        default=False,
        validation_alias=AliasChoices("AGENT_STRUCTURED_OUTPUT", "INKWELL_AGENT_STRUCTURED_OUTPUT"),
    )

    # ADR-0006: non-streaming output cap for the agent call.
    agent_max_tokens: int = 16000

    # Stage 13 kill-switch (default OFF). When false, POST/PATCH /spaces return 403
    # with error.code="disabled"; GET /spaces is unaffected. Accepts a bare
    # ``SPACES_EDITABLE`` in addition to the ``INKWELL_``-prefixed form.
    spaces_editable: bool = Field(
        default=False,
        validation_alias=AliasChoices("SPACES_EDITABLE", "INKWELL_SPACES_EDITABLE"),
    )

    # Stage 21 kill-switch (default OFF). When false, `inkwell push` exits 2 with
    # "push disabled" and the blob routes (POST /blobs, GET /blobs/{key}) return 403
    # with error.code="disabled". Gates the whole server-side push surface (ADR-0012).
    # Accepts a bare ``PUSH_ENABLED`` in addition to the ``INKWELL_``-prefixed form.
    push_enabled: bool = Field(
        default=False,
        validation_alias=AliasChoices("PUSH_ENABLED", "INKWELL_PUSH_ENABLED"),
    )

    # Stage 25 kill-switch (default OFF, ADR-0013). When false, brain_writes are NOT
    # persisted (and no brain_entry_ids sibling is added to the job result), the three
    # /brain/{space_slug} routes return 403 with error.code="disabled", and the
    # save_to_brain card action keeps returning 422 not_implemented. Accepts a bare
    # ``BRAIN_ENABLED`` in addition to the ``INKWELL_``-prefixed form.
    brain_enabled: bool = Field(
        default=False,
        validation_alias=AliasChoices("BRAIN_ENABLED", "INKWELL_BRAIN_ENABLED"),
    )

    env: str = "dev"

    # NOTE: ANTHROPIC_API_KEY is intentionally NOT a field here. The anthropic SDK
    # reads it straight from the process environment on the server; keeping it out of
    # Settings means it can never be serialised or logged by accident (SPEC §11).


@lru_cache
def get_settings() -> Settings:
    return Settings()


def raw_database_url() -> str:
    """DATABASE_URL wins if set (CI service container / compose), else INKWELL_*."""
    import os

    return os.environ.get("DATABASE_URL", get_settings().database_url)
