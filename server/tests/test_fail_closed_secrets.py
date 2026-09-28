"""Stage 37: fail closed on default/empty/weak secrets outside dev; docs routes off.

Settings are built directly (``Settings(...)``) or via env + ``get_settings.cache_clear()``;
the ``_fresh_settings`` fixture clears the lru_cache before AND after so a staging config
never leaks into the rest of the (dev) suite. Every failure asserts that the variable is
named and that the secret value is absent from the message and from captured output.
"""

from __future__ import annotations

import logging

import pytest
from fastapi.testclient import TestClient

from app.config import (
    DEV_BLOB_SIGNING_KEY,
    DEV_TOKEN_PEPPER,
    ConfigError,
    Settings,
    get_settings,
)

# Throwaway >= 32-char values: low-entropy and obviously fake by construction, distinct
# from each other and from the dev defaults (never real secrets).
GOOD_PEPPER = "test-pepper-" + "p" * 32
GOOD_BLOB = "test-blobkey-" + "b" * 32
GOOD_PREVIOUS = "test-previous-" + "o" * 32
SHORT_31 = "s" * 31
MARKED_31 = "short-marker-" + "q" * 18  # 31 chars, distinctive for leak checks


def _settings(env: str = "staging", **overrides) -> Settings:
    values = {
        "env": env,
        "token_pepper": GOOD_PEPPER,
        "blob_signing_key": GOOD_BLOB,
        "token_pepper_previous": "",
    }
    values.update(overrides)
    return Settings(**values)


@pytest.fixture
def _fresh_settings():
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def _assert_rejects(var: str, secret: str, **overrides) -> str:
    with pytest.raises(ConfigError) as info:
        _settings(**overrides)
    msg = str(info.value)
    assert var in msg
    if secret.strip():
        assert secret not in msg
    return msg


# ---- regression: these configurations started the server before Stage 37 ----


@pytest.mark.parametrize("env", ["staging", "prod"])
def test_empty_pepper_rejected(env):
    msg = _assert_rejects("INKWELL_TOKEN_PEPPER", "", env=env, token_pepper="")
    assert "empty" in msg


def test_whitespace_pepper_rejected():
    msg = _assert_rejects("INKWELL_TOKEN_PEPPER", " " * 40, token_pepper=" " * 40)
    assert "empty" in msg


@pytest.mark.parametrize("env", ["staging", "prod"])
def test_default_pepper_rejected(env):
    msg = _assert_rejects(
        "INKWELL_TOKEN_PEPPER", DEV_TOKEN_PEPPER, env=env, token_pepper=DEV_TOKEN_PEPPER
    )
    assert "dev default" in msg


def test_default_blob_key_rejected():
    msg = _assert_rejects(
        "INKWELL_BLOB_SIGNING_KEY", DEV_BLOB_SIGNING_KEY, blob_signing_key=DEV_BLOB_SIGNING_KEY
    )
    assert "dev default" in msg


def test_empty_blob_key_rejected():
    _assert_rejects("INKWELL_BLOB_SIGNING_KEY", "", blob_signing_key="")


def test_31_char_pepper_rejected():
    assert len(MARKED_31) == 31
    msg = _assert_rejects("INKWELL_TOKEN_PEPPER", MARKED_31, token_pepper=MARKED_31)
    assert "at least 32" in msg


def test_31_char_blob_key_rejected():
    _assert_rejects("INKWELL_BLOB_SIGNING_KEY", SHORT_31, blob_signing_key=SHORT_31)


def test_env_var_path_names_variable_not_value(monkeypatch, _fresh_settings):
    """The real deployment path: values arrive via INKWELL_* env vars (compose)."""
    monkeypatch.setenv("INKWELL_ENV", "staging")
    monkeypatch.setenv("INKWELL_TOKEN_PEPPER", MARKED_31)
    monkeypatch.setenv("INKWELL_BLOB_SIGNING_KEY", GOOD_BLOB)
    monkeypatch.delenv("INKWELL_TOKEN_PEPPER_PREVIOUS", raising=False)
    with pytest.raises(ConfigError) as info:
        get_settings()
    assert "INKWELL_TOKEN_PEPPER" in str(info.value)
    assert MARKED_31 not in str(info.value)


# ---- the other rules ----


def test_pepper_equal_to_blob_key_rejected():
    msg = _assert_rejects("INKWELL_BLOB_SIGNING_KEY", GOOD_PEPPER, blob_signing_key=GOOD_PEPPER)
    assert "INKWELL_TOKEN_PEPPER" in msg and "different" in msg


def test_previous_equal_to_pepper_rejected():
    msg = _assert_rejects(
        "INKWELL_TOKEN_PEPPER_PREVIOUS", GOOD_PEPPER, token_pepper_previous=GOOD_PEPPER
    )
    assert "differ" in msg


def test_previous_short_rejected():
    _assert_rejects("INKWELL_TOKEN_PEPPER_PREVIOUS", MARKED_31, token_pepper_previous=MARKED_31)


def test_previous_default_rejected():
    _assert_rejects(
        "INKWELL_TOKEN_PEPPER_PREVIOUS", DEV_TOKEN_PEPPER, token_pepper_previous=DEV_TOKEN_PEPPER
    )


@pytest.mark.parametrize("previous", ["", None, GOOD_PREVIOUS])
def test_valid_staging_config_accepted(previous):
    s = _settings(token_pepper_previous=previous)
    assert s.env == "staging"


def test_valid_prod_config_accepted():
    assert _settings(env="prod").env == "prod"


@pytest.mark.parametrize("env", ["prd", "production", "Staging", "", "test"])
def test_unknown_env_rejected(env):
    with pytest.raises(ConfigError) as info:
        _settings(env=env)
    assert "INKWELL_ENV" in str(info.value)


def test_dev_defaults_still_fine():
    s = Settings(env="dev", token_pepper=DEV_TOKEN_PEPPER, blob_signing_key=DEV_BLOB_SIGNING_KEY)
    assert s.env == "dev"
    # dev is exempt from every rule, including empty and short secrets.
    assert Settings(env="dev", token_pepper="", blob_signing_key="x").env == "dev"


def test_all_failures_reported_together():
    with pytest.raises(ConfigError) as info:
        _settings(token_pepper="", blob_signing_key=DEV_BLOB_SIGNING_KEY)
    msg = str(info.value)
    assert "INKWELL_TOKEN_PEPPER " in msg and "INKWELL_BLOB_SIGNING_KEY" in msg


# ---- API entry point + docs routes ----


def _staging_env(monkeypatch, pepper: str | None = GOOD_PEPPER, blob: str = GOOD_BLOB):
    monkeypatch.setenv("INKWELL_ENV", "staging")
    if pepper is None:
        monkeypatch.delenv("INKWELL_TOKEN_PEPPER", raising=False)
    else:
        monkeypatch.setenv("INKWELL_TOKEN_PEPPER", pepper)
    monkeypatch.setenv("INKWELL_BLOB_SIGNING_KEY", blob)
    monkeypatch.delenv("INKWELL_TOKEN_PEPPER_PREVIOUS", raising=False)


def test_create_app_refuses_missing_secret_under_staging(
    monkeypatch, _fresh_settings, capsys, caplog
):
    from app.main import create_app

    _staging_env(monkeypatch, pepper=None, blob=MARKED_31)
    with caplog.at_level(logging.DEBUG), pytest.raises(ConfigError) as info:
        create_app()
    msg = str(info.value)
    assert "INKWELL_TOKEN_PEPPER" in msg and "INKWELL_BLOB_SIGNING_KEY" in msg
    out = capsys.readouterr()
    for text in (msg, out.out, out.err, caplog.text):
        assert MARKED_31 not in text


def test_create_app_refuses_empty_pepper_under_staging(monkeypatch, _fresh_settings):
    """Compose turns an unset ${INKWELL_TOKEN_PEPPER} into an EMPTY string."""
    from app.main import create_app

    _staging_env(monkeypatch, pepper="")
    with pytest.raises(ConfigError, match="INKWELL_TOKEN_PEPPER"):
        create_app()


def test_staging_app_hides_docs_but_serves_health(monkeypatch, _fresh_settings):
    from app.main import create_app

    _staging_env(monkeypatch)
    client = TestClient(create_app(), raise_server_exceptions=False)
    for path in ("/openapi.json", "/docs", "/redoc", "/docs/oauth2-redirect"):
        assert client.get(path).status_code == 404, path
    health = client.get("/v1/health")
    assert health.status_code == 200
    assert health.json()["status"] == "ok"


def test_dev_app_still_serves_docs(monkeypatch, _fresh_settings):
    from app.main import create_app

    monkeypatch.setenv("INKWELL_ENV", "dev")
    client = TestClient(create_app(), raise_server_exceptions=False)
    assert client.get("/openapi.json").status_code == 200
    assert client.get("/docs").status_code == 200


# ---- CLI entry point: `inkwell config check` and fail-closed for every command ----


def test_cli_config_check_ok(monkeypatch, _fresh_settings, capsys):
    from app.cli import main

    _staging_env(monkeypatch)
    assert main(["config", "check"]) == 0
    out = capsys.readouterr()
    assert out.out.strip() == "config ok (env=staging)"
    assert GOOD_PEPPER not in out.out + out.err
    assert GOOD_BLOB not in out.out + out.err


def test_cli_config_check_ok_in_dev(monkeypatch, _fresh_settings, capsys):
    from app.cli import main

    monkeypatch.setenv("INKWELL_ENV", "dev")
    assert main(["config", "check"]) == 0
    assert capsys.readouterr().out.strip() == "config ok (env=dev)"


def test_cli_config_check_fails_naming_variable(monkeypatch, _fresh_settings, capsys):
    from app.cli import main

    _staging_env(monkeypatch, pepper=MARKED_31)
    assert main(["config", "check"]) != 0
    out = capsys.readouterr()
    assert "INKWELL_TOKEN_PEPPER" in out.err
    assert "at least 32" in out.err
    assert "config ok" not in out.out
    assert MARKED_31 not in out.out + out.err


def test_cli_config_check_rejects_unknown_env(monkeypatch, _fresh_settings, capsys):
    from app.cli import main

    monkeypatch.setenv("INKWELL_ENV", "prd")
    assert main(["config", "check"]) != 0
    assert "INKWELL_ENV" in capsys.readouterr().err


@pytest.mark.parametrize("argv", [["db", "seed"], ["token", "list"]])
def test_cli_every_command_fails_closed(monkeypatch, _fresh_settings, capsys, argv):
    """db seed / token commands refuse to run (and never touch the DB) on a bad config."""
    import app.cli as cli

    def _boom(*_a, **_k):  # pragma: no cover - must not be reached
        raise AssertionError("command ran despite an insecure configuration")

    monkeypatch.setattr(cli, "get_sessionmaker", _boom)
    _staging_env(monkeypatch, pepper="")
    assert cli.main(argv) != 0
    assert "INKWELL_TOKEN_PEPPER" in capsys.readouterr().err


# ---- worker entry point ----


def test_worker_refuses_to_start_under_staging(monkeypatch, _fresh_settings):
    import app.worker as worker

    def _boom(*_a, **_k):  # pragma: no cover - must not be reached
        raise AssertionError("worker reached the claim loop despite an insecure config")

    monkeypatch.setattr(worker, "_listen_connection", _boom)
    monkeypatch.setattr(worker, "_drain", _boom)
    _staging_env(monkeypatch, blob=DEV_BLOB_SIGNING_KEY)
    with pytest.raises(ConfigError, match="INKWELL_BLOB_SIGNING_KEY"):
        worker.run()
