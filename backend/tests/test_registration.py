import time

import pytest
from fastapi.testclient import TestClient
from nacl.signing import SigningKey

from packetgen import Victim


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("JILLA_KEY_DIR", str(tmp_path / "keys"))
    monkeypatch.setenv("JILLA_DB", str(tmp_path / "t.db"))
    import importlib
    import main
    importlib.reload(main)
    with TestClient(main.app) as c:
        yield c


def test_register_with_sim_number(client):
    r = client.post("/register", json=Victim("Asha").registration())
    assert r.status_code == 200 and r.json()["identified_by"] == "phone"


def test_register_without_sim_number_uses_device_id(client):
    r = client.post("/register", json=Victim("Asha").registration(phone="", device_id="b3a8-uuid"))
    assert r.status_code == 200 and r.json()["identified_by"] == "device_id"


def test_cannot_register_a_key_you_do_not_own(client):
    body = Victim("Asha").registration()
    body["identity_pub"] = bytes(SigningKey.generate().verify_key).hex()  # someone else's key
    r = client.post("/register", json=body)
    assert r.status_code == 401 and "signature" in r.json()["detail"]


def test_tampered_fields_and_stale_requests_rejected(client):
    body = Victim("Asha").registration()
    body["phone"] = "+910000000000"  # attacker swaps the number after signing
    assert client.post("/register", json=body).status_code == 401
    old = Victim("Asha").registration(ts_ms=int(time.time() * 1000) - 60 * 60 * 1000)
    assert client.post("/register", json=old).status_code == 401
    assert client.post("/register", json=Victim("A").registration(phone="abc")).status_code == 400


def test_dashboard_served(client):
    r = client.get("/")
    assert r.status_code == 200 and "Jilla Authority Console" in r.text


def test_dashboard_token(tmp_path, monkeypatch):
    monkeypatch.setenv("JILLA_KEY_DIR", str(tmp_path / "keys"))
    monkeypatch.setenv("JILLA_DB", str(tmp_path / "t.db"))
    monkeypatch.setenv("JILLA_DASH_TOKEN", "s3cret")
    import importlib
    import main
    importlib.reload(main)
    with TestClient(main.app) as c:
        assert c.get("/").status_code == 401
        assert c.get("/dispatches").status_code == 401
        assert c.get("/?token=s3cret").status_code == 200
        assert c.get("/dispatches?token=s3cret").status_code == 200
        assert c.get("/authority").status_code == 200  # phone-facing stays open
