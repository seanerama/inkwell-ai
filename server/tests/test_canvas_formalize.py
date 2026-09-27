"""Integration: canvas.formalize through the API + worker with a fake client (Stage 12).

The device sends ``canvas.formalize`` with the source canvas id and ``meta.title``; the
agent returns a clean diagram (boxes, labelled arrows, text) plus one ``answer`` card.
The handler round-trips the diagram into ``jobs.result`` and, additionally, creates an
``origin=agent`` ``canvases`` row and adds the server-added ``canvas`` /
``source_canvas_id`` sibling keys to ``result`` next to ``contract_version``. The new
canvas is then listed by ``GET /canvases``.
"""

from __future__ import annotations

import pytest
from sqlalchemy import select

from app.agent import client as agent_client
from app.agent.prompt import build_system_prompt
from app.config import get_settings
from app.db.base import get_sessionmaker
from app.db.models import Canvas, Card, Job, Space
from app.jobs.handlers import get_job_handler
from app.jobs.queue import process_one
from tests.fakes import PNG_1X1_B64, FakeAnthropic, json_payload

# A recorded-shape canvas.formalize response for the owner's 3-node topology sketch:
# three aligned boxes, labelled arrows, node labels, and one summarising `answer` card.
FORMALIZE_RESPONSE = {
    "summary": "Redrew the topology as three aligned boxes with labelled links.",
    "annotations": [
        {"id": "b1", "type": "rect", "x": 0.1, "y": 0.1, "w": 0.2, "h": 0.1, "label": "Web"},
        {"id": "b2", "type": "rect", "x": 0.4, "y": 0.1, "w": 0.2, "h": 0.1, "label": "API"},
        {"id": "b3", "type": "rect", "x": 0.7, "y": 0.1, "w": 0.2, "h": 0.1, "label": "DB"},
        {"id": "a1", "type": "arrow", "from": [0.3, 0.15], "to": [0.4, 0.15], "label": "calls"},
        {"id": "a2", "type": "arrow", "from": [0.6, 0.15], "to": [0.7, 0.15], "label": "reads"},
    ],
    "cards": [
        {
            "kind": "answer",
            "title": "Cleaned up the topology",
            "body": "Aligned the three nodes on a grid and straightened the two links.",
            "anchors": [],
            "actions": [],
        }
    ],
    "brain_writes": [],
}


@pytest.fixture(autouse=True)
def _reset_client():
    yield
    agent_client.set_client(None)


@pytest.fixture
def agent_on(monkeypatch):
    monkeypatch.setattr(get_settings(), "agent_enabled", True)


def _default_space(db) -> Space:
    return db.execute(select(Space).order_by(Space.position)).scalars().first()


def _source_canvas(db, space: Space, *, width_cu: int = 1600, height_cu: int = 1200) -> Canvas:
    canvas = Canvas(
        space_id=space.id,
        title="Topology",
        width_cu=width_cu,
        height_cu=height_cu,
        origin="user",
    )
    db.add(canvas)
    db.commit()
    db.refresh(canvas)
    return canvas


def _submit(client, auth, **extra) -> str:
    body = {"type": "canvas.formalize", "image": PNG_1X1_B64, **extra}
    resp = client.post("/v1/jobs", json=body, headers=auth)
    assert resp.status_code == 202, resp.text
    return resp.json()["id"]


def _run_worker() -> None:
    sm = get_sessionmaker()
    with sm() as session:
        assert process_one(session, "test-worker") is True


def test_formalize_is_accepted_202_and_requires_image(client, auth):
    # No longer 422 not_implemented; accepted at POST /jobs.
    job_id = _submit(client, auth)
    got = client.get(f"/v1/jobs/{job_id}", headers=auth).json()
    assert got["type"] == "canvas.formalize"
    assert got["status"] == "queued"
    # Image is required, held by key only.
    assert got["request"]["image_key"].endswith(".png")

    missing = client.post("/v1/jobs", json={"type": "canvas.formalize"}, headers=auth)
    assert missing.status_code == 422
    assert missing.json()["error"]["code"] == "validation"


def test_formalize_has_registered_handler():
    assert get_job_handler("canvas.formalize") is not None
    # Same type-agnostic handler as annotate/ask.
    assert get_job_handler("canvas.formalize") is get_job_handler("canvas.annotate")


def test_formalize_end_to_end_creates_agent_canvas_and_sibling_keys(client, auth, agent_on, db):
    space = _default_space(db)
    source = _source_canvas(db, space)

    fake = FakeAnthropic(json_payload(FORMALIZE_RESPONSE))
    agent_client.set_client(fake)
    job_id = _submit(
        client,
        auth,
        space_id=str(space.id),
        canvas_id=str(source.id),
        meta={"title": "Topology"},
    )

    _run_worker()

    got = client.get(f"/v1/jobs/{job_id}", headers=auth).json()
    assert got["status"] == "done"
    result = got["result"]
    assert result["contract_version"] == "agent-output/v1"
    assert len(result["annotations"]) == 5

    # A new agent-origin canvas row exists, in the same space, same dims as the source.
    canvases = db.execute(select(Canvas).where(Canvas.origin == "agent")).scalars().all()
    assert len(canvases) == 1
    new_canvas = canvases[0]
    assert new_canvas.space_id == space.id
    assert new_canvas.width_cu == source.width_cu
    assert new_canvas.height_cu == source.height_cu
    assert new_canvas.title == "Topology — formalized"

    # The server-added sibling keys point at it (never produced by the model).
    assert result["canvas"]["id"] == str(new_canvas.id)
    assert result["canvas"]["origin"] == "agent"
    assert result["canvas"]["title"] == "Topology — formalized"
    assert result["canvas"]["width_cu"] == source.width_cu
    assert result["source_canvas_id"] == str(source.id)

    # Exactly one answer card round-trips.
    cards = db.execute(select(Card).where(Card.job_id == job_id)).scalars().all()
    assert len(cards) == 1
    assert cards[0].kind == "answer"

    # effort was "high".
    call = fake.messages.calls[0]
    assert call["output_config"]["effort"] == "high"


def test_formalize_default_title_when_no_meta(client, auth, agent_on, db):
    space = _default_space(db)
    fake = FakeAnthropic(json_payload(FORMALIZE_RESPONSE))
    agent_client.set_client(fake)
    # No canvas_id and no meta: default dims + "Formalized" title, source_canvas_id null.
    job_id = _submit(client, auth, space_id=str(space.id))
    _run_worker()

    got = client.get(f"/v1/jobs/{job_id}", headers=auth).json()
    assert got["status"] == "done"
    assert got["result"]["canvas"]["title"] == "Formalized"
    assert got["result"]["canvas"]["width_cu"] == 2480
    assert got["result"]["canvas"]["height_cu"] == 3508
    assert got["result"]["source_canvas_id"] is None


def test_get_canvases_lists_agent_canvas_and_honours_space_id(client, auth, agent_on, db):
    space = _default_space(db)
    source = _source_canvas(db, space)
    fake = FakeAnthropic(json_payload(FORMALIZE_RESPONSE))
    agent_client.set_client(fake)
    job_id = _submit(
        client, auth, space_id=str(space.id), canvas_id=str(source.id), meta={"title": "Topology"}
    )
    _run_worker()
    new_id = client.get(f"/v1/jobs/{job_id}", headers=auth).json()["result"]["canvas"]["id"]

    listed = client.get("/v1/canvases", headers=auth)
    assert listed.status_code == 200
    ids = [c["id"] for c in listed.json()]
    assert new_id in ids
    # Only the agent-origin canvas is listed (the user source canvas is not).
    assert str(source.id) not in ids

    # space_id filter keeps it when the space matches...
    same = client.get(f"/v1/canvases?space_id={space.id}", headers=auth).json()
    assert new_id in [c["id"] for c in same]
    # ...and drops it for a different space.
    import uuid as _uuid

    other = client.get(f"/v1/canvases?space_id={_uuid.uuid4()}", headers=auth).json()
    assert other == []


def test_formalize_guidance_present_and_effort_high():
    prompt = build_system_prompt(job_type="canvas.formalize")
    assert "Task: redraw the sketch as a clean diagram" in prompt
    assert "`rect` for boxes" in prompt
    assert agent_client.effort_for("canvas.formalize") == "high"


def test_ask_canary_result_has_no_canvas_sibling_keys(client, auth, agent_on, db):
    """The formalize sibling keys are formalize-only: an ask result is unchanged."""
    ask_response = {
        "summary": "Answered the note.",
        "annotations": [{"id": "t1", "type": "text", "at": [0.4, 0.2], "text": "10", "size": 0.02}],
        "cards": [
            {"kind": "answer", "title": "1 + 9 = 10", "body": "10.", "anchors": [], "actions": []}
        ],
        "brain_writes": [],
    }
    fake = FakeAnthropic(json_payload(ask_response))
    agent_client.set_client(fake)
    resp = client.post("/v1/jobs", json={"type": "canvas.ask", "image": PNG_1X1_B64}, headers=auth)
    job_id = resp.json()["id"]
    _run_worker()

    result = client.get(f"/v1/jobs/{job_id}", headers=auth).json()["result"]
    assert "canvas" not in result
    assert "source_canvas_id" not in result
    # And no agent canvas row was created by the ask job.
    assert db.execute(select(Job).where(Job.id == job_id)).scalar_one().type == "canvas.ask"
    assert db.execute(select(Canvas).where(Canvas.origin == "agent")).scalars().all() == []


# --- Stage 35 (ADR-0014 §6): the new canvas is sized from the exported region ---


def _formalized_dims(client, auth, db, **extra) -> tuple[int, int]:
    space = _default_space(db)
    source = _source_canvas(db, space)
    agent_client.set_client(FakeAnthropic(json_payload(FORMALIZE_RESPONSE)))
    job_id = _submit(client, auth, space_id=str(space.id), canvas_id=str(source.id), **extra)
    _run_worker()
    got = client.get(f"/v1/jobs/{job_id}", headers=auth).json()
    assert got["status"] == "done"
    new_id = got["result"]["canvas"]["id"]
    row = db.execute(select(Canvas).where(Canvas.origin == "agent")).scalars().one()
    assert str(row.id) == new_id
    assert (got["result"]["canvas"]["width_cu"], got["result"]["canvas"]["height_cu"]) == (
        row.width_cu,
        row.height_cu,
    )
    return row.width_cu, row.height_cu


def test_formalize_sizes_new_canvas_from_the_export_region(client, auth, agent_on, db):
    # A landscape region one page right of the origin (the device-api example): the new
    # canvas is the region's size, not the 1600x1200 source canvas.
    export = {
        "w": 1568,
        "h": 1109,
        "width_cu": 3508,
        "height_cu": 2480,
        "origin_x_cu": 2480,
        "origin_y_cu": 0,
    }
    assert _formalized_dims(client, auth, db, export=export) == (3508, 2480)
    # The export is stored as given (opaque), origin keys included.
    job = db.execute(select(Job).where(Job.type == "canvas.formalize")).scalars().one()
    assert job.request["export"] == export


def test_formalize_region_without_origin_keys_still_sizes_from_the_region(
    client, auth, agent_on, db
):
    export = {"w": 1568, "h": 1176, "width_cu": 2000, "height_cu": 1500}
    assert _formalized_dims(client, auth, db, export=export) == (2000, 1500)


def test_formalize_without_export_sizes_keeps_the_source_canvas_size(client, auth, agent_on, db):
    # No export at all, and an export without sizes: unchanged (the source canvas size).
    assert _formalized_dims(client, auth, db) == (1600, 1200)


@pytest.mark.parametrize(
    "export",
    [
        {"w": 1568, "h": 1109},
        {"width_cu": 0, "height_cu": 2480},
        {"width_cu": -5, "height_cu": 2480},
        {"width_cu": 3508.5, "height_cu": 2480},
        {"width_cu": "3508", "height_cu": 2480},
        {"width_cu": True, "height_cu": 2480},
        {"width_cu": 3508, "height_cu": None},
        {"width_cu": 3508, "height_cu": 2**31},
    ],
)
def test_formalize_ignores_a_garbage_region_and_falls_back(client, auth, agent_on, db, export):
    assert _formalized_dims(client, auth, db, export=export) == (1600, 1200)


def test_formalize_region_without_source_canvas_sizes_from_region(client, auth, agent_on, db):
    space = _default_space(db)
    agent_client.set_client(FakeAnthropic(json_payload(FORMALIZE_RESPONSE)))
    job_id = _submit(
        client,
        auth,
        space_id=str(space.id),
        export={"w": 1568, "h": 1109, "width_cu": 3508, "height_cu": 2480},
    )
    _run_worker()
    got = client.get(f"/v1/jobs/{job_id}", headers=auth).json()
    assert got["result"]["canvas"]["width_cu"] == 3508
    assert got["result"]["canvas"]["height_cu"] == 2480


def test_formalize_accepts_a_region_up_to_the_legibility_floor(client, auth, agent_on, db):
    # 7016 CU (2 x the longest A4 edge) is the largest region the device exports.
    export = {"w": 1568, "h": 784, "width_cu": 7016, "height_cu": 3508}
    assert _formalized_dims(client, auth, db, export=export) == (7016, 3508)


@pytest.mark.parametrize(
    "export",
    [
        {"width_cu": 7017, "height_cu": 3508},
        {"width_cu": 3508, "height_cu": 7017},
        {"width_cu": 100_000, "height_cu": 100_000},
    ],
)
def test_formalize_region_past_the_floor_falls_back_to_the_source(
    client, auth, agent_on, db, export
):
    assert _formalized_dims(client, auth, db, export=export) == (1600, 1200)
