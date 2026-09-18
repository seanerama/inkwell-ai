"""Generation-based pepper rotation that fails closed (Stage 20, ADR-0008).

Stage 18 shipped the dual-pepper grace window but `migrate-check` used
MAX(hash_version), which false-greens the instant a rotation begins (before any token
migrates, MAX is still the old value, so nothing "lags"). Stage 20 records an explicit
`pepper_generation` in `app_meta`: `rotate-pepper --begin` bumps it, `verify_token`
stamps a re-hashed row with the CURRENT generation, and `migrate-check` is red while the
previous pepper is set AND a live token still lags that generation.

Peppers are swapped with monkeypatch.setenv + get_settings.cache_clear(); the autouse
fixture clears the cache on teardown so a mutated Settings never leaks into another test.
"""

from __future__ import annotations

import argparse

import pytest

from app.cli import (
    _cmd_token_migrate_check,
    _cmd_token_rotate_pepper,
)
from app.config import get_settings
from app.security.pepper import get_pepper_generation
from app.security.tokens import create_token, hash_token, verify_token


@pytest.fixture(autouse=True)
def _reset_settings_cache():
    yield
    get_settings.cache_clear()


def _set_peppers(monkeypatch, *, current: str, previous: str | None) -> None:
    monkeypatch.setenv("INKWELL_TOKEN_PEPPER", current)
    if previous is None:
        monkeypatch.delenv("INKWELL_TOKEN_PEPPER_PREVIOUS", raising=False)
    else:
        monkeypatch.setenv("INKWELL_TOKEN_PEPPER_PREVIOUS", previous)
    get_settings.cache_clear()


def _begin_ns() -> argparse.Namespace:
    return argparse.Namespace(begin=True, end=False)


def _end_ns() -> argparse.Namespace:
    return argparse.Namespace(begin=False, end=True)


def test_rotate_pepper_begin_bumps_generation(db):
    assert get_pepper_generation(db) == 1  # missing row -> generation 1
    assert _cmd_token_rotate_pepper(_begin_ns()) == 0
    db.expire_all()
    assert get_pepper_generation(db) == 2
    # A second begin keeps climbing.
    assert _cmd_token_rotate_pepper(_begin_ns()) == 0
    db.expire_all()
    assert get_pepper_generation(db) == 3


def test_full_rotation_flow_red_then_green(db, monkeypatch):
    # Mint under the old pepper, single-pepper steady state.
    _set_peppers(monkeypatch, current="pep-old", previous=None)
    _, plaintext = create_token(db, "tablet")

    # Operator begins the rotation: generation -> 2, then swaps the env peppers.
    assert _cmd_token_rotate_pepper(_begin_ns()) == 0
    _set_peppers(monkeypatch, current="pep-new", previous="pep-old")

    # Before the tablet syncs, migrate-check must be RED (fails closed): previous set and
    # the live token is still on generation 1 < 2.
    assert _cmd_token_migrate_check(argparse.Namespace()) == 1
    # And --end must refuse while red.
    assert _cmd_token_rotate_pepper(_end_ns()) == 1

    # The tablet's next request verifies under the previous pepper and is re-hashed and
    # stamped with the current generation (2).
    row = verify_token(db, plaintext)
    assert row is not None
    assert row.hash_version == 2
    assert row.token_hash == hash_token(plaintext)  # now the current-pepper hash

    # migrate-check is now GREEN, and --end is allowed.
    assert _cmd_token_migrate_check(argparse.Namespace()) == 0
    assert _cmd_token_rotate_pepper(_end_ns()) == 0


def test_no_previous_pepper_single_lookup_and_green(db, monkeypatch):
    # No rotation in progress: verify_token does a single current-pepper lookup and
    # migrate-check is green even for a token minted under a different pepper.
    _set_peppers(monkeypatch, current="pep-old", previous=None)
    _, plaintext = create_token(db, "tablet")

    _set_peppers(monkeypatch, current="pep-new", previous=None)
    assert verify_token(db, plaintext) is None  # no fallback lookup, no spurious match
    assert _cmd_token_migrate_check(argparse.Namespace()) == 0


def test_migrate_check_red_only_while_previous_set(db, monkeypatch):
    # A lagging live token alone is NOT red; it is red only when the previous pepper is
    # also set (the window is actually open).
    from app.security.pepper import set_pepper_generation

    set_pepper_generation(db, 2)
    create_token(db, "lagger")  # v1 < 2

    _set_peppers(monkeypatch, current="pep-new", previous=None)
    assert _cmd_token_migrate_check(argparse.Namespace()) == 0  # window closed -> green

    _set_peppers(monkeypatch, current="pep-new", previous="pep-old")
    assert _cmd_token_migrate_check(argparse.Namespace()) == 1  # window open -> red
