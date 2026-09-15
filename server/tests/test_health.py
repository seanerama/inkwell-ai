from app import __version__
from app.api.errors import CONTRACT_HEADER, CONTRACT_VALUE


def test_health_ok_with_version_and_contract_header(client):
    resp = client.get("/v1/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["version"] == __version__
    assert body["contract"] == CONTRACT_VALUE
    assert resp.headers[CONTRACT_HEADER] == CONTRACT_VALUE


def test_health_is_unauthenticated(client):
    # No Authorization header, still 200.
    assert client.get("/v1/health").status_code == 200


def test_version_is_derived_from_the_environment(monkeypatch):
    import importlib

    import app as app_pkg

    monkeypatch.setenv("INKWELL_VERSION", "9.9.9")
    importlib.reload(app_pkg)
    assert app_pkg.__version__ == "9.9.9"
    monkeypatch.delenv("INKWELL_VERSION")
    importlib.reload(app_pkg)
    assert app_pkg.__version__ == "0.0.0-dev"
