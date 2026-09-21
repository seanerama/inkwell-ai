"""Stage 25: the three frozen /brain/{space_slug} routes (ADR-0013, contract device-api).

Search is Postgres-specific (websearch_to_tsquery over the generated tsvector), so these
run against the real Postgres from conftest.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select

from app.brain.store import compute_hash, normalise_tags
from app.config import get_settings
from app.db.models import BrainEntry


@pytest.fixture
def brain_on(monkeypatch):
    monkeypatch.setattr(get_settings(), "brain_enabled", True)


@pytest.fixture
def agent_token(db) -> str:
    from app.security.tokens import create_token

    _, plaintext = create_token(db, "test-agent", kind="agent")
    return plaintext


def _seed(db, *, text, tags, slug="work", created_at=None) -> BrainEntry:
    entry = BrainEntry(
        space_slug=slug,
        kind="fact",
        text=text,
        tags=normalise_tags(tags),
        hash=compute_hash(text),
    )
    if created_at is not None:
        entry.created_at = created_at
    db.add(entry)
    db.commit()
    db.refresh(entry)
    return entry


# ---- auth matrix -----------------------------------------------------------------


def test_no_token_401(client, brain_on):
    resp = client.get("/v1/brain/work")
    assert resp.status_code == 401


def test_agent_token_403(client, brain_on, agent_token):
    resp = client.get("/v1/brain/work", headers={"Authorization": f"Bearer {agent_token}"})
    assert resp.status_code == 403


# ---- kill-switch -----------------------------------------------------------------


def test_switch_off_403_disabled_on_all_three(client, auth, db):
    assert get_settings().brain_enabled is False
    e = _seed(db, text="anything", tags=[])
    get_ = client.get("/v1/brain/work", headers=auth)
    post = client.post("/v1/brain/work", json={"kind": "fact", "text": "x"}, headers=auth)
    delete = client.delete(f"/v1/brain/work/{e.id}", headers=auth)
    for resp in (get_, post, delete):
        assert resp.status_code == 403
        assert resp.json()["error"]["code"] == "disabled"


# ---- GET / search ----------------------------------------------------------------


def test_unknown_slug_404(client, auth, brain_on):
    resp = client.get("/v1/brain/nope", headers=auth)
    assert resp.status_code == 404


def test_search_text_ranks_above_tag_ranks_above_recency(client, auth, brain_on, db):
    now = datetime.now(UTC)
    # Text match (weight A) is the OLDEST; tag match (weight B) is the NEWEST. If rank
    # beats recency, the text match still comes first.
    _seed(db, text="quarterly budget planning", tags=["misc"], created_at=now - timedelta(hours=2))
    _seed(db, text="a note about the weather", tags=["budget"], created_at=now)
    resp = client.get("/v1/brain/work?q=budget", headers=auth)
    assert resp.status_code == 200
    rows = resp.json()
    assert len(rows) == 2
    assert rows[0]["text"] == "quarterly budget planning"  # text (A) beats tag (B) + recency
    assert rows[1]["tags"] == ["budget"]


def test_search_no_match_returns_empty(client, auth, brain_on, db):
    _seed(db, text="quarterly budget planning", tags=["finance"])
    resp = client.get("/v1/brain/work?q=elephant", headers=auth)
    assert resp.status_code == 200
    assert resp.json() == []


def test_list_without_q_is_newest_first(client, auth, brain_on, db):
    now = datetime.now(UTC)
    _seed(db, text="older entry", tags=[], created_at=now - timedelta(hours=1))
    _seed(db, text="newer entry", tags=[], created_at=now)
    rows = client.get("/v1/brain/work", headers=auth).json()
    assert [r["text"] for r in rows] == ["newer entry", "older entry"]


# ---- POST idempotent -------------------------------------------------------------


def test_post_creates_201_then_dupe_returns_200_existing(client, auth, brain_on, db):
    body = {"kind": "fact", "text": "Remember the milk", "tags": ["Shopping", "shopping"]}
    first = client.post("/v1/brain/work", json=body, headers=auth)
    assert first.status_code == 201, first.text
    created = first.json()
    assert created["tags"] == ["shopping"]  # normalised
    assert created["job_id"] is None  # device-created

    # A duplicate (normalised text matches) returns 200 with the SAME existing entry.
    dupe = client.post(
        "/v1/brain/work", json={"kind": "task", "text": "  remember   the MILK "}, headers=auth
    )
    assert dupe.status_code == 200, dupe.text
    assert dupe.json()["id"] == created["id"]
    assert len(db.execute(select(BrainEntry)).scalars().all()) == 1


def test_post_unknown_slug_404(client, auth, brain_on):
    resp = client.post("/v1/brain/nope", json={"kind": "fact", "text": "x"}, headers=auth)
    assert resp.status_code == 404


# ---- DELETE ----------------------------------------------------------------------


def test_delete_soft_deletes_and_hides(client, auth, brain_on, db):
    e = _seed(db, text="delete me", tags=[])
    resp = client.delete(f"/v1/brain/work/{e.id}", headers=auth)
    assert resp.status_code == 204
    # Gone from listings...
    assert client.get("/v1/brain/work", headers=auth).json() == []
    # ...but the row is retained with deleted_at set (soft delete).
    row = db.get(BrainEntry, e.id)
    db.refresh(row)
    assert row.deleted_at is not None


def test_delete_unknown_id_404(client, auth, brain_on):
    import uuid

    resp = client.delete(f"/v1/brain/work/{uuid.uuid4()}", headers=auth)
    assert resp.status_code == 404


def test_delete_wrong_space_404(client, auth, brain_on, db):
    e = _seed(db, text="in work space", tags=[], slug="work")
    resp = client.delete(f"/v1/brain/home/{e.id}", headers=auth)
    assert resp.status_code == 404
    # Still live in its real space.
    assert db.get(BrainEntry, e.id).deleted_at is None
