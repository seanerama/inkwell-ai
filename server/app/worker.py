"""Worker entrypoint (``python -m app.worker``): the Postgres queue claim loop.

Loop: sweep expired leases (ADR-0003), drain all queued jobs, then wait on
``LISTEN/NOTIFY`` for the next insert or a short poll timeout, whichever comes first.
Handlers are registered via ``app.jobs.register_handler``; Stage 1 ships only
``system.ping``.
"""

from __future__ import annotations

import os
import signal
import socket
import time

from app.config import get_settings, raw_database_url
from app.db.base import get_sessionmaker
from app.jobs import handlers  # noqa: F401  (registers system.ping on import)
from app.jobs.queue import NOTIFY_CHANNEL, process_one, sweep_expired_leases
from app.logging import configure_logging, get_logger

log = get_logger("worker")
_running = True


def _stop(*_a) -> None:
    global _running
    _running = False


def _worker_id() -> str:
    return f"{socket.gethostname()}:{os.getpid()}"


def _drain(worker_id: str) -> int:
    sm = get_sessionmaker()
    processed = 0
    while _running:
        session = sm()
        try:
            sweep_expired_leases(session)
            if not process_one(session, worker_id):
                break
            processed += 1
        finally:
            session.close()
    return processed


def _listen_connection():
    """A dedicated psycopg connection in LISTEN mode; None if unavailable."""
    try:
        import psycopg

        dsn = raw_database_url().replace("postgresql+psycopg://", "postgresql://")
        conn = psycopg.connect(dsn, autocommit=True)
        conn.execute(f"LISTEN {NOTIFY_CHANNEL}")
        return conn
    except Exception as exc:  # fall back to pure polling
        log.info("worker.listen_unavailable", error=str(exc))
        return None


def run() -> None:
    configure_logging()
    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)

    worker_id = _worker_id()
    settings = get_settings()
    log.info("worker.start", worker_id=worker_id, poll_seconds=settings.worker_poll_seconds)

    conn = _listen_connection()
    try:
        while _running:
            _drain(worker_id)
            if not _running:
                break
            if conn is not None:
                # Block up to the poll interval waiting for a NOTIFY; return early the
                # moment one arrives (stop_after=1), then loop back to drain. psycopg's
                # bare notifies() generator is unbounded, so the timeout is required.
                try:
                    for _ in conn.notifies(timeout=settings.worker_poll_seconds, stop_after=1):
                        break
                except Exception as exc:
                    log.info("worker.notify_wait_failed", error=str(exc))
                    time.sleep(settings.worker_poll_seconds)
            else:
                time.sleep(settings.worker_poll_seconds)
    finally:
        if conn is not None:
            conn.close()
        log.info("worker.stop", worker_id=worker_id)


if __name__ == "__main__":
    run()
