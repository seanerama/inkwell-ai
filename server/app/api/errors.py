"""The device-api error envelope: ``{ "error": { "code", "message" } }``."""

from __future__ import annotations

from fastapi import Request
from fastapi.responses import JSONResponse

CONTRACT_HEADER = "X-Inkwell-Contract"
CONTRACT_VALUE = "device-api/v1"


class ApiError(Exception):
    def __init__(
        self, status_code: int, code: str, message: str, headers: dict | None = None
    ) -> None:
        self.status_code = status_code
        self.code = code
        self.message = message
        self.headers = headers
        super().__init__(message)


def error_response(status_code: int, code: str, message: str, headers: dict | None = None):
    payload = {"error": {"code": code, "message": message}}
    hdrs = {CONTRACT_HEADER: CONTRACT_VALUE}
    if headers:
        hdrs.update(headers)
    return JSONResponse(status_code=status_code, content=payload, headers=hdrs)


async def api_error_handler(_: Request, exc: ApiError) -> JSONResponse:
    return error_response(exc.status_code, exc.code, exc.message, exc.headers)


async def validation_error_handler(_: Request, exc) -> JSONResponse:
    return error_response(422, "validation", "request failed validation")


async def unhandled_error_handler(_: Request, exc: Exception) -> JSONResponse:
    return error_response(500, "internal", "internal server error")
