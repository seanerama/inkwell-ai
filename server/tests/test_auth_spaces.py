from app.security.tokens import create_token, revoke_token


def test_spaces_unauthenticated_401(client):
    resp = client.get("/v1/spaces")
    assert resp.status_code == 401
    assert resp.json()["error"]["code"] == "unauthorized"


def test_spaces_revoked_token_401(client, db):
    row, plaintext = create_token(db, "doomed")
    revoke_token(db, str(row.id))
    resp = client.get("/v1/spaces", headers={"Authorization": f"Bearer {plaintext}"})
    assert resp.status_code == 401


def test_spaces_minted_token_returns_four_seeded_spaces(client, auth):
    resp = client.get("/v1/spaces", headers=auth)
    assert resp.status_code == 200
    spaces = resp.json()
    assert [s["slug"] for s in spaces] == ["work", "home", "learning", "business"]


def test_malformed_authorization_header_401(client):
    resp = client.get("/v1/spaces", headers={"Authorization": "Token abc"})
    assert resp.status_code == 401
