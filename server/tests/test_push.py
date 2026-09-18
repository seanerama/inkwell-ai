"""Stage 21: the push service, the inkwell push CLI, canvas detail, and /sync ordering."""

from __future__ import annotations

from pathlib import Path

import pytest
from sqlalchemy import select

from app import cli
from app.config import get_settings
from app.db.models import Canvas, Job, Layer, Raster, Space
from app.jobs import queue
from app.push.service import PushError, push_canvas, push_document

FIXTURES = Path(__file__).parent / "fixtures"
FROZEN_KEYS = {"canvas", "layers", "rasters", "cards"}


@pytest.fixture
def push_on(monkeypatch):
    monkeypatch.setattr(get_settings(), "push_enabled", True)


def _space(db, slug="work") -> Space:
    return db.execute(select(Space).where(Space.slug == slug)).scalar_one()


# --- push_document -------------------------------------------------------------------


def test_push_single_page_pdf_frozen_shape_plus_url(db):
    data = (FIXTURES / "one_page.pdf").read_bytes()
    job, canvases = push_document(db, _space(db), data, title="Brief")
    assert job.direction == "to_user"
    assert job.type == "agent.push_document"
    assert job.status == "done"
    assert len(canvases) == 1

    canvas = canvases[0]
    assert canvas.origin == "agent"
    assert canvas.title == "Brief"
    layers = db.execute(select(Layer).where(Layer.canvas_id == canvas.id)).scalars().all()
    assert len(layers) == 1
    assert layers[0].owner == "agent" and layers[0].type == "raster" and layers[0].z == -1
    assert layers[0].job_id == job.id
    rasters = db.execute(select(Raster).where(Raster.layer_id == layers[0].id)).scalars().all()
    assert len(rasters) == 1
    assert rasters[0].blob_uri.startswith("push/") and rasters[0].mime == "application/pdf"

    result = job.result
    # Frozen shape exactly, plus the additive `canvases` sibling.
    assert FROZEN_KEYS <= set(result)
    assert set(result) == FROZEN_KEYS | {"canvases"}
    assert result["canvas"]["id"] == str(canvas.id)
    assert len(result["canvases"]) == 1
    assert result["rasters"][0]["url"].startswith("/v1/blobs/push/")
    assert result["rasters"][0]["mime"] == "application/pdf"


def test_push_three_page_pdf_makes_three_canvases(db):
    data = (FIXTURES / "three_page.pdf").read_bytes()
    job, canvases = push_document(db, _space(db), data, title="Report")
    assert len(canvases) == 3
    titles = [c.title for c in canvases]
    assert titles == ["Report — p1", "Report — p2", "Report — p3"]
    assert len(job.result["canvases"]) == 3
    assert len(job.result["rasters"]) == 3
    # One blob shared by every page, distinct pages.
    keys = {r["blob_uri"] for r in job.result["rasters"]}
    assert len(keys) == 1
    assert [r["page"] for r in job.result["rasters"]] == [1, 2, 3]


def test_push_png(db):
    data = (FIXTURES / "pixel.png").read_bytes()
    job, canvases = push_document(db, _space(db), data, title="Shot")
    assert len(canvases) == 1
    assert job.result["rasters"][0]["mime"] == "image/png"
    assert job.result["rasters"][0]["url"].startswith("/v1/blobs/push/")


def test_push_over_twenty_pages_rejected(db):
    data = (FIXTURES / "twentyone_page.pdf").read_bytes()
    with pytest.raises(PushError):
        push_document(db, _space(db), data, title="Too long")


def test_push_note_creates_answer_card(db):
    data = (FIXTURES / "one_page.pdf").read_bytes()
    job, _ = push_document(db, _space(db), data, title="Brief", card_body="Please review")
    assert len(job.result["cards"]) == 1
    assert job.result["cards"][0]["kind"] == "answer"
    assert job.result["cards"][0]["body"] == "Please review"


# --- push_canvas ---------------------------------------------------------------------


def test_push_canvas_blank_no_layers(db):
    job, canvas = push_canvas(db, _space(db), "Sketch", 2480, 3508)
    assert job.type == "agent.push_canvas"
    assert job.status == "done"
    assert canvas.origin == "agent" and canvas.title == "Sketch"
    layers = db.execute(select(Layer).where(Layer.canvas_id == canvas.id)).scalars().all()
    assert layers == []
    assert job.result["layers"] == [] and job.result["rasters"] == []
    assert set(job.result) == FROZEN_KEYS


# --- /sync ordering ------------------------------------------------------------------


def test_sync_returns_to_user_job_after_a_to_agent_job(client, auth, db):
    space = _space(db)
    to_agent = queue.enqueue(db, space_id=space.id, job_type="canvas.ask", direction="to_agent")
    data = (FIXTURES / "one_page.pdf").read_bytes()
    push_job, _ = push_document(db, space, data, title="Brief")

    resp = client.get("/v1/sync", headers=auth)
    assert resp.status_code == 200
    ids = [j["id"] for j in resp.json()["jobs"]]
    assert str(to_agent.id) in ids
    assert str(push_job.id) in ids
    assert ids.index(str(push_job.id)) > ids.index(str(to_agent.id))
    pushed = next(j for j in resp.json()["jobs"] if j["id"] == str(push_job.id))
    assert pushed["direction"] == "to_user"
    assert pushed["result"]["rasters"][0]["url"].startswith("/v1/blobs/push/")


# --- canvas detail -------------------------------------------------------------------


def test_canvas_detail_returns_rasters_with_verifiable_url(client, auth, db, push_on):
    data = (FIXTURES / "one_page.pdf").read_bytes()
    _, canvases = push_document(db, _space(db), data, title="Brief")
    cid = canvases[0].id

    resp = client.get(f"/v1/canvases/{cid}", headers=auth)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["id"] == str(cid)
    assert len(body["layers"]) == 1
    assert len(body["rasters"]) == 1
    url = body["rasters"][0]["url"]
    assert url.startswith("/v1/blobs/push/")

    # The signed url actually resolves to the bytes (bearer + signature).
    got = client.get(url, headers=auth)
    assert got.status_code == 200
    assert got.content == data


def test_canvas_detail_unknown_404(client, auth):
    import uuid

    resp = client.get(f"/v1/canvases/{uuid.uuid4()}", headers=auth)
    assert resp.status_code == 404
    assert resp.json()["error"]["code"] == "not_found"


# --- CLI -----------------------------------------------------------------------------


def test_cli_push_disabled_exits_2(capsys, tmp_path):
    assert get_settings().push_enabled is False
    f = tmp_path / "x.pdf"
    f.write_bytes((FIXTURES / "one_page.pdf").read_bytes())
    rc = cli.main(["push", "document", "--space", "work", "--file", str(f)])
    assert rc == 2
    assert "push disabled" in capsys.readouterr().err


def test_cli_push_document_creates_job(db, push_on):
    f = FIXTURES / "one_page.pdf"
    rc = cli.main(["push", "document", "--space", "work", "--file", str(f), "--title", "CLI Brief"])
    assert rc == 0
    jobs = db.execute(select(Job).where(Job.type == "agent.push_document")).scalars().all()
    assert len(jobs) == 1
    canvases = db.execute(select(Canvas).where(Canvas.title == "CLI Brief")).scalars().all()
    assert len(canvases) == 1


def test_cli_push_canvas_landscape(db, push_on):
    rc = cli.main(["push", "canvas", "--space", "work", "--title", "Wide", "--landscape"])
    assert rc == 0
    canvas = db.execute(select(Canvas).where(Canvas.title == "Wide")).scalar_one()
    assert canvas.width_cu == 3508 and canvas.height_cu == 2480


def test_cli_push_unknown_space_exits_1(db, push_on):
    rc = cli.main(["push", "canvas", "--space", "nope-nope", "--title", "X"])
    assert rc == 1
