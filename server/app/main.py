"""FastAPI application factory (command ``api``)."""

from __future__ import annotations

from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError

from app import __version__
from app.api.errors import (
    ApiError,
    api_error_handler,
    unhandled_error_handler,
    validation_error_handler,
)
from app.api.middleware import ContractAndLoggingMiddleware
from app.api.ratelimit import RateLimiter
from app.api.routes import cards, health, jobs, spaces, sync, usage
from app.config import get_settings
from app.logging import configure_logging


def create_app() -> FastAPI:
    configure_logging()
    settings = get_settings()

    app = FastAPI(title="Inkwell AI server", version=__version__)
    app.state.rate_limiter = RateLimiter(settings.job_rate_limit_per_min)

    app.add_middleware(ContractAndLoggingMiddleware)

    app.add_exception_handler(ApiError, api_error_handler)
    app.add_exception_handler(RequestValidationError, validation_error_handler)
    app.add_exception_handler(Exception, unhandled_error_handler)

    for module in (health, spaces, jobs, cards, sync, usage):
        app.include_router(module.router, prefix="/v1")

    return app


app = create_app()
