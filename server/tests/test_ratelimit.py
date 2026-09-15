def test_thirty_first_post_jobs_in_a_minute_is_429_with_retry_after(client, auth):
    for _ in range(30):
        resp = client.post("/v1/jobs", json={"type": "system.ping"}, headers=auth)
        assert resp.status_code == 202
    resp = client.post("/v1/jobs", json={"type": "system.ping"}, headers=auth)
    assert resp.status_code == 429
    assert resp.json()["error"]["code"] == "rate_limited"
    assert "retry-after" in {k.lower() for k in resp.headers}
