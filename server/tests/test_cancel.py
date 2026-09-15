from app.db.base import get_sessionmaker
from app.jobs.queue import claim_one


def _submit(client, auth) -> str:
    resp = client.post("/v1/jobs", json={"type": "system.ping"}, headers=auth)
    assert resp.status_code == 202
    return resp.json()["id"]


def test_cancel_queued_is_immediate(client, auth):
    job_id = _submit(client, auth)
    resp = client.post(f"/v1/jobs/{job_id}/cancel", headers=auth)
    assert resp.status_code == 200
    assert resp.json()["status"] == "cancelled"


def test_cancel_running_sets_flag(client, auth):
    job_id = _submit(client, auth)
    sm = get_sessionmaker()
    with sm() as session:
        claim_one(session, "test-worker")  # moves it to running
    resp = client.post(f"/v1/jobs/{job_id}/cancel", headers=auth)
    assert resp.status_code == 200
    assert resp.json()["status"] == "running"
    from app.db.models import Job

    with sm() as session:
        import uuid

        job = session.get(Job, uuid.UUID(job_id))
        assert job.cancel_requested is True


def test_cancel_terminal_job_409(client, auth):
    job_id = _submit(client, auth)
    client.post(f"/v1/jobs/{job_id}/cancel", headers=auth)  # -> cancelled
    resp = client.post(f"/v1/jobs/{job_id}/cancel", headers=auth)
    assert resp.status_code == 409
