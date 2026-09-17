"""Dual-pepper grace window (Stage 18, ADR-0008).

verify_token must: hit on the current pepper; on a current-pepper miss fall back to the
previous pepper when one is set, re-hashing the row to the current pepper and bumping
hash_version so the NEXT request hits on the current pepper alone; never resurrect a
revoked row under either pepper; and, with no previous pepper set, do a single lookup
that does not spuriously match. `inkwell token migrate-check` exit codes are covered too.

Peppers are swapped with monkeypatch.setenv + get_settings.cache_clear(); the autouse
fixture below clears the cache again on teardown so the mutated Settings never leaks into
another test.
"""

from __future__ import annotations

import pytest

from app.cli import _cmd_token_migrate_check
from app.config import get_settings
from app.security.tokens import create_token, hash_token, revoke_token, verify_token


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


def test_current_pepper_hit(db, monkeypatch):
    _set_peppers(monkeypatch, current="pep-current", previous=None)
    _, plaintext = create_token(db, "tablet")

    row = verify_token(db, plaintext)
    assert row is not None
    assert row.hash_version == 1
    assert row.last_seen_at is not None


def test_unknown_token_returns_none(db, monkeypatch):
    _set_peppers(monkeypatch, current="pep-current", previous="pep-old")
    assert verify_token(db, "never-minted") is None


def test_no_previous_pepper_single_lookup_no_spurious_match(db, monkeypatch):
    # Token minted under an OLD pepper; verifying under a new pepper with NO previous set
    # must miss (no fallback lookup, no spurious match).
    _set_peppers(monkeypatch, current="pep-old", previous=None)
    _, plaintext = create_token(db, "tablet")

    _set_peppers(monkeypatch, current="pep-new", previous=None)
    assert verify_token(db, plaintext) is None


def test_previous_pepper_hit_rehashes_and_bumps_version(db, monkeypatch):
    # Mint under the old pepper.
    _set_peppers(monkeypatch, current="pep-old", previous=None)
    row, plaintext = create_token(db, "tablet")
    token_id = row.id
    old_stored_hash = row.token_hash

    # Rotate: new current, old kept as previous.
    _set_peppers(monkeypatch, current="pep-new", previous="pep-old")
    got = verify_token(db, plaintext)
    assert got is not None
    assert got.id == token_id
    assert got.hash_version == 2
    # The stored hash is now the current-pepper hash, not the old one.
    assert got.token_hash != old_stored_hash
    assert got.token_hash == hash_token(plaintext)

    # NEXT request: drop the previous pepper entirely; a current-pepper lookup must hit
    # and the version must not climb further.
    _set_peppers(monkeypatch, current="pep-new", previous=None)
    again = verify_token(db, plaintext)
    assert again is not None
    assert again.id == token_id
    assert again.hash_version == 2


def test_revoked_under_current_pepper_returns_none(db, monkeypatch):
    _set_peppers(monkeypatch, current="pep-current", previous=None)
    row, plaintext = create_token(db, "doomed")
    revoke_token(db, str(row.id))
    assert verify_token(db, plaintext) is None


def test_revoked_under_previous_pepper_is_not_resurrected(db, monkeypatch):
    # Mint + revoke under the old pepper, then rotate. The previous-pepper lookup finds
    # the row but it is revoked -> None, and it is NOT re-hashed/resurrected.
    _set_peppers(monkeypatch, current="pep-old", previous=None)
    row, plaintext = create_token(db, "doomed")
    token_id = row.id
    revoke_token(db, str(row.id))

    _set_peppers(monkeypatch, current="pep-new", previous="pep-old")
    assert verify_token(db, plaintext) is None

    db.expire_all()
    from app.db.models import DeviceToken

    still = db.get(DeviceToken, token_id)
    assert still.revoked_at is not None
    assert still.hash_version == 1  # untouched


def test_migrate_check_all_migrated_exit_0(db, monkeypatch):
    _set_peppers(monkeypatch, current="pep-current", previous=None)
    # Two live tokens, both on the same (newest) generation -> no straggler.
    create_token(db, "a")
    create_token(db, "b")
    assert _cmd_token_migrate_check(argparse_ns()) == 0


def test_migrate_check_straggler_exit_1(db, monkeypatch):
    _set_peppers(monkeypatch, current="pep-current", previous=None)
    row_a, _ = create_token(db, "a")  # will be advanced to v2
    create_token(db, "b")  # stays v1 -> the straggler
    row_a.hash_version = 2
    db.commit()
    assert _cmd_token_migrate_check(argparse_ns()) == 1


def test_migrate_check_no_tokens_exit_0(db, monkeypatch):
    _set_peppers(monkeypatch, current="pep-current", previous=None)
    assert _cmd_token_migrate_check(argparse_ns()) == 0


def test_migrate_check_revoked_straggler_ignored_exit_0(db, monkeypatch):
    _set_peppers(monkeypatch, current="pep-current", previous=None)
    row_a, _ = create_token(db, "a")
    row_b, _ = create_token(db, "b")
    row_a.hash_version = 2  # newest generation
    db.commit()
    revoke_token(db, str(row_b.id))  # b lags but is revoked -> not a live straggler
    assert _cmd_token_migrate_check(argparse_ns()) == 0


def argparse_ns():
    import argparse

    return argparse.Namespace()
