"""Structured JSON logging (ADR-0009): one JSON object per line."""

from __future__ import annotations

import logging
import os
import sys
from typing import Any

import structlog

_REDACTION = "[REDACTED]"
# Env var names whose values must never reach a log line, even at debug (SPEC §11).
_SECRET_ENV_VARS = ("ANTHROPIC_API_KEY", "INKWELL_TOKEN_PEPPER", "INKWELL_BLOB_SIGNING_KEY")


def redact_secrets(_logger: Any, _method: str, event_dict: dict) -> dict:
    """Structlog processor: scrub any known secret value out of every rendered field.

    Defence-in-depth for the API key (SPEC §11): even if some code path passes a
    secret into a log call, its literal value is replaced before the line is written.
    """
    secrets = [v for name in _SECRET_ENV_VARS if (v := os.environ.get(name))]
    if not secrets:
        return event_dict
    for field, value in list(event_dict.items()):
        if isinstance(value, str):
            for secret in secrets:
                if secret and secret in value:
                    value = value.replace(secret, _REDACTION)
            event_dict[field] = value
    return event_dict


def configure_logging() -> None:
    logging.basicConfig(format="%(message)s", stream=sys.stdout, level=logging.INFO)
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True),
            redact_secrets,
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(logging.INFO),
        logger_factory=structlog.PrintLoggerFactory(),
        cache_logger_on_first_use=True,
    )


def get_logger(name: str = "inkwell") -> structlog.stdlib.BoundLogger:
    return structlog.get_logger(name)
