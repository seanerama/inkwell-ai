"""Stage 23: the agent push API — token kinds, the auth matrix, and the push routes.

Covers the headline acceptance (device tokens cannot push; agent tokens cannot read the
device routes), the two ``POST /v1/push/…`` routes (limits, kill-switch, rate limit), and
the additive ``device_tokens.kind`` migration default.
"""

from __future__ import annotations

import uuid
from pathlib import Path

import pytest
from sqlalchemy import select, text

from app.config import get_settings
from app.db.models import Canvas, Job
from app.security.tokens import create_token, revoke_token

FIXTURES = Path(__file__).parent / "fixtures"


@pytest.fixture
def push_on(monkeypatch):
    monkeypatch.setattr(get_settings(), "push_enabled", True)


@pytest.fixture
def agent_token(db) -> str:
    _, plaintext = create_token(db, "test-agent", kind="agent")
    return plaintext


@pytest.fixture
def agent_auth(agent_token: str) -> dict:
    return {"Authorization": f"Bearer {agent_token}"}


def _doc(
    client, headers, *, fixture="one_page.pdf", space="work", filename="brief.pdf", title="Brief"
):
    data = (FIXTURES / fixture).read_bytes()
    return client.post(
        "/v1/push/document",
        files={"file": (filename, data, "application/octet-stream")},
        data={"space": space, "title": title},
        headers=headers,
    )


# --- token kinds ---------------------------------------------------------------------


def test_create_token_defaults_to_device_kind(db):
    row, _ = create_token(db, "d")
    assert row.kind == "device"


def test_create_agent_token_records_kind(db):
    row, _ = create_token(db, "a", kind="agent")
    assert row.kind == "agent"


def test_create_token_rejects_bad_kind(db):
    with pytest.raises(ValueError):
        create_token(db, "x", kind="robot")


def test_existing_rows_default_to_device_kind(db):
    """The additive migration backfills legacy rows (inserted without ``kind``)."""
    tid = str(uuid.uuid4())
    db.execute(
        text("INSERT INTO device_tokens (id, name, token_hash) VALUES (:id, 'legacy', :h)"),
        {"id": tid, "h": "legacyhash"},
    )
    db.commit()
    kind = db.execute(
        text("SELECT kind FROM device_tokens WHERE id = :id"), {"id": tid}
    ).scalar_one()
    assert kind == "device"


# --- auth matrix (the headline acceptance) -------------------------------------------


def test_device_token_cannot_push_document_403(client, auth, push_on):
    resp = _doc(client, auth)
    assert resp.status_code == 403
    assert resp.json()["error"]["code"] == "forbidden"


def test_device_token_cannot_push_canvas_403(client, auth, push_on):
    resp = client.post("/v1/push/canvas", json={"space": "work", "title": "X"}, headers=auth)
    assert resp.status_code == 403
    assert resp.json()["error"]["code"] == "forbidden"


@pytest.mark.parametrize(
    "method,url",
    [
        ("get", "/v1/sync"),
        ("get", "/v1/spaces"),
        ("get", "/v1/canvases"),
        ("get", f"/v1/jobs/{uuid.uuid4()}"),
    ],
)
def test_agent_token_rejected_on_device_routes_403(client, agent_auth, method, url):
    resp = getattr(client, method)(url, headers=agent_auth)
    assert resp.status_code == 403
    assert resp.json()["error"]["code"] == "forbidden"


def test_agent_token_can_push_201(client, agent_auth, push_on):
    resp = _doc(client, agent_auth)
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert "job_id" in body and len(body["canvas_ids"]) == 1


def test_revoked_agent_token_401(client, db, push_on):
    row, plaintext = create_token(db, "gone", kind="agent")
    revoke_token(db, str(row.id))
    resp = _doc(client, {"Authorization": f"Bearer {plaintext}"})
    assert resp.status_code == 401
    assert resp.json()["error"]["code"] == "unauthorized"


def test_no_token_401(client, push_on):
    resp = _doc(client, {})
    assert resp.status_code == 401


# --- push document -------------------------------------------------------------------


def test_push_single_page_pdf_201(client, agent_auth, db, push_on):
    resp = _doc(client, agent_auth, fixture="one_page.pdf")
    assert resp.status_code == 201, resp.text
    assert len(resp.json()["canvas_ids"]) == 1
    job = db.execute(select(Job).where(Job.type == "agent.push_document")).scalar_one()
    assert job.status == "done" and job.direction == "to_user"


def test_push_three_page_pdf_makes_three_canvases(client, agent_auth, push_on):
    resp = _doc(client, agent_auth, fixture="three_page.pdf", title="Report")
    assert resp.status_code == 201, resp.text
    assert len(resp.json()["canvas_ids"]) == 3


def test_push_png_201(client, agent_auth, push_on):
    resp = _doc(client, agent_auth, fixture="pixel.png", filename="p.png")
    assert resp.status_code == 201, resp.text
    assert len(resp.json()["canvas_ids"]) == 1


def test_push_oversize_413(client, agent_auth, push_on, monkeypatch):
    monkeypatch.setattr("app.api.routes.push.MAX_BLOB_BYTES", 8)
    resp = client.post(
        "/v1/push/document",
        files={"file": ("f.pdf", b"%PDF-1.4 well over the tiny cap", "x")},
        data={"space": "work"},
        headers=agent_auth,
    )
    assert resp.status_code == 413
    assert resp.json()["error"]["code"] == "too_large"


def test_push_wrong_mime_415(client, agent_auth, push_on):
    resp = client.post(
        "/v1/push/document",
        files={"file": ("evil.pdf", b"just text, not an image", "x")},
        data={"space": "work"},
        headers=agent_auth,
    )
    assert resp.status_code == 415
    assert resp.json()["error"]["code"] == "unsupported_media_type"


def test_push_unknown_space_404(client, agent_auth, push_on):
    resp = _doc(client, agent_auth, space="nope-nope")
    assert resp.status_code == 404
    assert resp.json()["error"]["code"] == "not_found"


def test_push_over_twenty_pages_422(client, agent_auth, push_on):
    resp = _doc(client, agent_auth, fixture="twentyone_page.pdf", title="Too long")
    assert resp.status_code == 422
    assert resp.json()["error"]["code"] == "validation"


def test_push_disabled_by_default_403(client, agent_auth):
    assert get_settings().push_enabled is False
    resp = _doc(client, agent_auth)
    assert resp.status_code == 403
    assert resp.json()["error"]["code"] == "disabled"


def test_push_rate_limited_after_ten(client, agent_auth, push_on):
    for _ in range(10):
        assert _doc(client, agent_auth).status_code == 201
    resp = _doc(client, agent_auth)
    assert resp.status_code == 429
    assert resp.json()["error"]["code"] == "rate_limited"
    assert "Retry-After" in resp.headers


# --- push canvas ---------------------------------------------------------------------


def test_push_canvas_201(client, agent_auth, db, push_on):
    resp = client.post(
        "/v1/push/canvas", json={"space": "work", "title": "Sketch"}, headers=agent_auth
    )
    assert resp.status_code == 201, resp.text
    assert len(resp.json()["canvas_ids"]) == 1
    canvas = db.execute(select(Canvas).where(Canvas.title == "Sketch")).scalar_one()
    assert canvas.origin == "agent"
    assert canvas.width_cu == 2480 and canvas.height_cu == 3508


def test_push_canvas_landscape_swaps_dims(client, agent_auth, db, push_on):
    resp = client.post(
        "/v1/push/canvas",
        json={"space": "work", "title": "Wide", "landscape": True},
        headers=agent_auth,
    )
    assert resp.status_code == 201, resp.text
    canvas = db.execute(select(Canvas).where(Canvas.title == "Wide")).scalar_one()
    assert canvas.width_cu == 3508 and canvas.height_cu == 2480


def test_push_canvas_disabled_403(client, agent_auth):
    resp = client.post("/v1/push/canvas", json={"space": "work", "title": "X"}, headers=agent_auth)
    assert resp.status_code == 403
    assert resp.json()["error"]["code"] == "disabled"


def test_push_canvas_unknown_space_404(client, agent_auth, push_on):
    resp = client.post(
        "/v1/push/canvas", json={"space": "nope-nope", "title": "X"}, headers=agent_auth
    )
    assert resp.status_code == 404
