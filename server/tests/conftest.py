"""Test fixtures: a real Postgres, the real migration, and a TestClient.

DATABASE_URL selects the database (CI service container or deploy/compose.test.yml
locally). The schema is built by running Alembic migration 0001 against a clean
``public`` schema, so the tests exercise the real migration, not ``create_all``.
"""

from __future__ import annotations

import os

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import text

os.environ.setdefault("INKWELL_TOKEN_PEPPER", "test-pepper")


@pytest.fixture(scope="session", autouse=True)
def _schema():
    from alembic import command
    from alembic.config import Config

    from app.db.base import get_engine

    engine = get_engine()
    with engine.begin() as conn:
        conn.execute(text("DROP SCHEMA public CASCADE"))
        conn.execute(text("CREATE SCHEMA public"))
    cfg = Config("alembic.ini")
    command.upgrade(cfg, "head")
    yield


@pytest.fixture(autouse=True)
def _clean():
    from app.db.base import get_sessionmaker
    from app.db.seed import seed_default_spaces
    from app.main import app

    sm = get_sessionmaker()
    with sm() as session:
        session.execute(text("TRUNCATE cards, jobs, device_tokens RESTART IDENTITY CASCADE"))
        session.commit()
        seed_default_spaces(session)
    app.state.rate_limiter.reset()
    yield


@pytest.fixture
def client() -> TestClient:
    from app.main import app

    return TestClient(app, raise_server_exceptions=False)


@pytest.fixture
def db():
    from app.db.base import get_sessionmaker

    sm = get_sessionmaker()
    session = sm()
    try:
        yield session
    finally:
        session.close()


@pytest.fixture
def token(db) -> str:
    from app.security.tokens import create_token

    _, plaintext = create_token(db, "test-tablet")
    return plaintext


@pytest.fixture
def auth(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}
