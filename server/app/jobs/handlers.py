"""Worker job-handler registry.

Stage 1 registers only the internal ``system.ping`` handler. Stage 5 plugs the SPEC
``to_agent`` types in via ``register_handler(type, fn)``; until then those types are
rejected at submission with ``422 not_implemented``.
"""

from __future__ import annotations

from collections.abc import Callable

# A handler takes the job's ``request`` dict and returns the ``result`` dict.
Handler = Callable[[dict], dict]

_HANDLERS: dict[str, Handler] = {}


def register_handler(job_type: str, fn: Handler) -> None:
    _HANDLERS[job_type] = fn


def get_handler(job_type: str) -> Handler | None:
    return _HANDLERS.get(job_type)


def registered_types() -> list[str]:
    return sorted(_HANDLERS)


def _ping(request: dict) -> dict:
    return {"pong": True}


register_handler("system.ping", _ping)
