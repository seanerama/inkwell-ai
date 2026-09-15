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

    # ADR-0004: HMAC key for signed, expiring blob URLs.
    blob_signing_key: str = "dev-blob-key-change-me"

    # ADR-0004: LocalBlobStore root; keys are sharded by the first two characters.
    blob_dir: str = "/data/blobs"

    # ADR-0008: POST /jobs rate limit, submissions per token per minute.
    job_rate_limit_per_min: int = 30

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

    # ADR-0006: non-streaming output cap for the agent call.
    agent_max_tokens: int = 16000

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
