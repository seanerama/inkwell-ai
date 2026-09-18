"""Stage 21: POST /blobs + GET /blobs/{key}, gated by PUSH_ENABLED (default OFF).

Covers the frozen contract routes (ADR-0004/0012): multipart upload with magic-byte
sniffing, the 20 MB cap, the signed+bearer download, and the kill-switch.
"""

from __future__ import annotations

import time
from pathlib import Path

import pytest

from app.blobs import get_blob_store
from app.config import get_settings

FIXTURES = Path(__file__).parent / "fixtures"


@pytest.fixture
def push_on(monkeypatch):
    monkeypatch.setattr(get_settings(), "push_enabled", True)


def _upload(client, auth, data: bytes, filename: str = "f.pdf"):
    return client.post(
        "/v1/blobs", files={"file": (filename, data, "application/octet-stream")}, headers=auth
    )


# --- kill-switch + auth --------------------------------------------------------------


def test_post_requires_token_401(client):
    resp = client.post("/v1/blobs", files={"file": ("f.pdf", b"%PDF-1.4", "x")})
    assert resp.status_code == 401


def test_post_disabled_by_default_403(client, auth):
    assert get_settings().push_enabled is False
    resp = _upload(client, auth, b"%PDF-1.4 test")
    assert resp.status_code == 403
    assert resp.json()["error"]["code"] == "disabled"


def test_get_disabled_by_default_403(client, auth):
    resp = client.get("/v1/blobs/push/whatever.pdf?sig=x&exp=9999999999", headers=auth)
    assert resp.status_code == 403
    assert resp.json()["error"]["code"] == "disabled"


# --- upload / download round-trip ----------------------------------------------------


def test_upload_download_round_trip(client, auth, push_on):
    data = (FIXTURES / "one_page.pdf").read_bytes()
    resp = _upload(client, auth, data)
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["key"].startswith("push/") and body["key"].endswith(".pdf")
    assert "url" in body and "expires_at" in body

    got = client.get(body["url"], headers=auth)
    assert got.status_code == 200
    assert got.content == data
    assert got.headers["content-type"].startswith("application/pdf")
    assert got.headers["cache-control"].startswith("private, max-age=")


def test_png_upload_infers_image_png(client, auth, push_on):
    data = (FIXTURES / "pixel.png").read_bytes()
    resp = _upload(client, auth, data, filename="p.png")
    assert resp.status_code == 201, resp.text
    assert resp.json()["key"].endswith(".png")
    got = client.get(resp.json()["url"], headers=auth)
    assert got.status_code == 200
    assert got.headers["content-type"].startswith("image/png")


# --- validation errors ---------------------------------------------------------------


def test_oversize_413(client, auth, push_on, monkeypatch):
    monkeypatch.setattr("app.api.routes.blobs.MAX_BLOB_BYTES", 8)
    resp = _upload(client, auth, b"%PDF-1.4 this is well over the tiny cap")
    assert resp.status_code == 413
    assert resp.json()["error"]["code"] == "too_large"


def test_wrong_mime_415_by_magic_sniff(client, auth, push_on):
    # Declared-type is ignored; the bytes are not a PDF/PNG/JPEG.
    resp = _upload(client, auth, b"just some text, definitely not an image")
    assert resp.status_code == 415
    assert resp.json()["error"]["code"] == "unsupported_media_type"


def test_disguised_extension_still_sniffed_415(client, auth, push_on):
    # A .pdf name over non-PDF bytes must still be rejected.
    resp = _upload(client, auth, b"GIF89a not allowed", filename="evil.pdf")
    assert resp.status_code == 415


# --- signature enforcement -----------------------------------------------------------


def test_bad_signature_403(client, auth, push_on):
    data = (FIXTURES / "one_page.pdf").read_bytes()
    key = _upload(client, auth, data).json()["key"]
    exp = int(time.time()) + 300
    resp = client.get(f"/v1/blobs/{key}?sig=deadbeef&exp={exp}", headers=auth)
    assert resp.status_code == 403
    assert resp.json()["error"]["code"] == "forbidden"


def test_expired_signature_403(client, auth, push_on):
    data = (FIXTURES / "one_page.pdf").read_bytes()
    key = _upload(client, auth, data).json()["key"]
    past = int(time.time()) - 10
    sig = get_blob_store().sign(key, past)
    resp = client.get(f"/v1/blobs/{key}?sig={sig}&exp={past}", headers=auth)
    assert resp.status_code == 403


def test_missing_bearer_401_even_with_valid_signature(client, auth, push_on):
    data = (FIXTURES / "one_page.pdf").read_bytes()
    url = _upload(client, auth, data).json()["url"]
    resp = client.get(url)  # valid signature, but no Authorization header
    assert resp.status_code == 401


def test_unknown_key_404(client, auth, push_on):
    key = "push/does-not-exist.pdf"
    exp = int(time.time()) + 300
    sig = get_blob_store().sign(key, exp)
    resp = client.get(f"/v1/blobs/{key}?sig={sig}&exp={exp}", headers=auth)
    assert resp.status_code == 404
    assert resp.json()["error"]["code"] == "not_found"
