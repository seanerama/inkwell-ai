"""Contract-header echo + one structured log line per request (ADR-0009)."""

from __future__ import annotations

import time
import uuid

from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request

from app.api.errors import CONTRACT_HEADER, CONTRACT_VALUE
from app.logging import get_logger

log = get_logger("api")


class ContractAndLoggingMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        request_id = request.headers.get("x-request-id") or str(uuid.uuid4())
        started = time.perf_counter()
        response = await call_next(request)
        elapsed_ms = round((time.perf_counter() - started) * 1000, 2)
        response.headers[CONTRACT_HEADER] = CONTRACT_VALUE
        response.headers["x-request-id"] = request_id
        log.info(
            "request",
            request_id=request_id,
            route=request.url.path,
            method=request.method,
            status=response.status_code,
            ms=elapsed_ms,
        )
        return response
