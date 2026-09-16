from app.db.base import get_sessionmaker
from app.jobs.queue import process_one


def test_ping_job_roundtrips_through_worker_and_sync(client, auth):
    # Submit a system.ping job.
    resp = client.post("/v1/jobs", json={"type": "system.ping"}, headers=auth)
    assert resp.status_code == 202
    job = resp.json()
    assert job["status"] == "queued"
    job_id = job["id"]

    # /sync without a cursor returns the queued job and a cursor.
    sync1 = client.get("/v1/sync", headers=auth).json()
    assert any(j["id"] == job_id for j in sync1["jobs"])
    cursor = sync1["cursor"]
    assert cursor

    # Run one worker iteration in-process.
    sm = get_sessionmaker()
    with sm() as session:
        assert process_one(session, "test-worker") is True

    # GET /jobs/{id} is now done with {"pong": true}.
    got = client.get(f"/v1/jobs/{job_id}", headers=auth).json()
    assert got["status"] == "done"
    assert got["result"] == {"pong": True}

    # /sync with the post-done cursor: an empty page and the same cursor. Because the
    # job's updated_at advanced past the earlier cursor, fetch a fresh cursor first.
    fresh = client.get("/v1/sync", headers=auth).json()
    fresh_cursor = fresh["cursor"]
    empty = client.get(f"/v1/sync?cursor={fresh_cursor}", headers=auth).json()
    assert empty["jobs"] == []
    assert empty["cursor"] == fresh_cursor


def test_spec_job_type_returns_422_not_implemented(client, auth):
    # canvas.annotate (Stage 5) and canvas.ask (Stage 7) are implemented; the other
    # SPEC types still 422.
    resp = client.post("/v1/jobs", json={"type": "canvas.formalize"}, headers=auth)
    assert resp.status_code == 422
    assert resp.json()["error"]["code"] == "not_implemented"


def test_unknown_job_type_422_validation(client, auth):
    resp = client.post("/v1/jobs", json={"type": "nope.bogus"}, headers=auth)
    assert resp.status_code == 422
    assert resp.json()["error"]["code"] == "validation"


def test_get_unknown_job_404(client, auth):
    import uuid

    resp = client.get(f"/v1/jobs/{uuid.uuid4()}", headers=auth)
    assert resp.status_code == 404
