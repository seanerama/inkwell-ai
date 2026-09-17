"""Stage 13: POST /spaces + PATCH /spaces/{id}, gated by SPACES_EDITABLE (default OFF)."""

from __future__ import annotations

import pytest
from sqlalchemy import func, select

from app.config import get_settings
from app.db.models import Space


@pytest.fixture
def spaces_on(monkeypatch):
    monkeypatch.setattr(get_settings(), "spaces_editable", True)


def _learning_id(db) -> str:
    row = db.execute(select(Space).where(Space.slug == "learning")).scalar_one()
    return str(row.id)


# --- auth + kill-switch -------------------------------------------------------------


def test_post_requires_token_401(client):
    resp = client.post("/v1/spaces", json={"name": "Nope"})
    assert resp.status_code == 401
    assert resp.json()["error"]["code"] == "unauthorized"


def test_patch_requires_token_401(client, db):
    resp = client.patch(f"/v1/spaces/{_learning_id(db)}", json={"color": "#111111"})
    assert resp.status_code == 401


def test_post_disabled_by_default_403(client, auth):
    assert get_settings().spaces_editable is False
    resp = client.post("/v1/spaces", json={"name": "New Space"}, headers=auth)
    assert resp.status_code == 403
    assert resp.json()["error"]["code"] == "disabled"


def test_patch_disabled_by_default_403(client, auth, db):
    resp = client.patch(f"/v1/spaces/{_learning_id(db)}", json={"color": "#111111"}, headers=auth)
    assert resp.status_code == 403
    assert resp.json()["error"]["code"] == "disabled"


# --- create -------------------------------------------------------------------------


def test_create_happy_path_derives_slug_and_appends_position(client, auth, db, spaces_on):
    max_pos = db.execute(select(func.max(Space.position))).scalar()
    resp = client.post("/v1/spaces", json={"name": "Side Projects!"}, headers=auth)
    assert resp.status_code == 201, resp.text
    out = resp.json()
    assert out["slug"] == "side-projects"  # lowercase, non-alnum→-, trimmed
    assert out["position"] == max_pos + 1
    assert out["model"] == "claude-sonnet-5"
    assert out["color"] == "#000000"
    assert out["system_prompt"] == ""
    assert out["tools"] == []


def test_create_accepts_explicit_fields(client, auth, spaces_on):
    body = {
        "name": "Ops",
        "slug": "ops-team",
        "system_prompt": "You watch the systems.",
        "tools": ["search"],
        "model": "claude-sonnet-5",
        "color": "#AABBCC",
        "position": 9,
    }
    resp = client.post("/v1/spaces", json=body, headers=auth)
    assert resp.status_code == 201, resp.text
    out = resp.json()
    assert out["slug"] == "ops-team"
    assert out["position"] == 9
    assert out["color"] == "#AABBCC"
    assert out["tools"] == ["search"]


def test_create_duplicate_slug_409(client, auth, spaces_on):
    resp = client.post("/v1/spaces", json={"name": "Work"}, headers=auth)  # slug -> "work"
    assert resp.status_code == 409
    assert resp.json()["error"]["code"] == "conflict"


def test_create_bad_color_422(client, auth, spaces_on):
    resp = client.post("/v1/spaces", json={"name": "X", "color": "red"}, headers=auth)
    assert resp.status_code == 422
    assert resp.json()["error"]["code"] == "validation"


def test_create_bad_slug_422(client, auth, spaces_on):
    resp = client.post("/v1/spaces", json={"name": "X", "slug": "Bad Slug"}, headers=auth)
    assert resp.status_code == 422
    assert resp.json()["error"]["code"] == "validation"


def test_create_overlong_prompt_422(client, auth, spaces_on):
    resp = client.post("/v1/spaces", json={"name": "X", "system_prompt": "a" * 8001}, headers=auth)
    assert resp.status_code == 422
    assert resp.json()["error"]["code"] == "validation"


# --- update -------------------------------------------------------------------------


def test_patch_partial_update_leaves_other_fields(client, auth, db, spaces_on):
    sid = _learning_id(db)
    before = client.get("/v1/spaces", headers=auth).json()
    learning_before = next(s for s in before if s["id"] == sid)

    resp = client.patch(f"/v1/spaces/{sid}", json={"color": "#123456"}, headers=auth)
    assert resp.status_code == 200, resp.text
    out = resp.json()
    assert out["color"] == "#123456"
    # Everything else is untouched.
    assert out["name"] == learning_before["name"]
    assert out["slug"] == learning_before["slug"]
    assert out["system_prompt"] == learning_before["system_prompt"]
    assert out["position"] == learning_before["position"]


def test_patch_slug_is_immutable_422(client, auth, db, spaces_on):
    resp = client.patch(f"/v1/spaces/{_learning_id(db)}", json={"slug": "renamed"}, headers=auth)
    assert resp.status_code == 422
    body = resp.json()
    assert body["error"]["code"] == "validation"
    assert body["error"]["message"] == "slug is immutable"


def test_patch_unknown_id_404(client, auth, spaces_on):
    import uuid

    resp = client.patch(f"/v1/spaces/{uuid.uuid4()}", json={"color": "#111111"}, headers=auth)
    assert resp.status_code == 404
    assert resp.json()["error"]["code"] == "not_found"


def test_patch_bad_color_422(client, auth, db, spaces_on):
    resp = client.patch(f"/v1/spaces/{_learning_id(db)}", json={"color": "nope"}, headers=auth)
    assert resp.status_code == 422
    assert resp.json()["error"]["code"] == "validation"
