import json
import subprocess
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from packetgen import Victim

ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("JILLA_KEY_DIR", str(tmp_path / "keys"))
    monkeypatch.setenv("JILLA_DB", str(tmp_path / "t.db"))
    import importlib
    import main
    importlib.reload(main)
    with TestClient(main.app) as c:
        c.authority = bytes.fromhex(c.get("/authority").json()["x25519_pub"])
        yield c


def post(c, raw):
    return c.post("/sos", content=raw, headers={"content-type": "application/cbor"})


def registered(c, name="Asha"):
    v = Victim(name)
    assert c.post("/register", json=v.registration()).status_code == 200
    return v


def test_dispatch_then_dedup_then_update(client):
    inc = registered(client).incident()
    p1 = inc.packet([client.authority], note="first")
    r = post(client, p1)
    assert r.status_code == 200 and r.json()["status"] == "dispatch"
    # same packet arriving again via another relay path (ttl differs, signature still valid)
    import cbor2
    a = cbor2.loads(p1); a[0] = 3
    assert post(client, cbor2.dumps(a)).status_code == 208
    # next location update
    assert post(client, inc.packet([client.authority], lat=12.98)).json()["status"] == "update"
    d = client.get("/dispatches").json()
    assert [x["kind"] for x in d] == ["DISPATCH", "UPDATE"]
    assert d[0]["name"] == "Asha" and d[0]["lat"] == 12.97161 and d[0]["note"] == "first"


def test_base64_json_body(client):
    import base64
    inc = registered(client).incident()
    raw = inc.packet([client.authority])
    r = client.post("/sos", json={"packet": base64.b64encode(raw).decode()})
    assert r.status_code == 200


def test_unregistered_identity_dispatched_as_unverified(client):
    inc = Victim("Mallory").incident()
    r = post(client, inc.packet([client.authority]))
    assert r.status_code == 200 and r.json()["verified"] is False
    d = client.get("/dispatches").json()
    assert len(d) == 1 and d[0]["verified"] is False and d[0]["registered_name"] is None
    # updates of the same unverified incident still flow
    assert post(client, inc.packet([client.authority])).json()["status"] == "update"


def test_registered_identity_is_verified(client):
    inc = registered(client).incident()
    assert post(client, inc.packet([client.authority])).json()["verified"] is True


def test_unverified_flood_is_capped(client):
    import main
    codes = [post(client, Victim(f"bot{i}").incident().packet([client.authority])).status_code
             for i in range(main.UNVERIFIED_NEW_PER_MIN + 5)]
    assert codes.count(200) == main.UNVERIFIED_NEW_PER_MIN and codes.count(429) == 5
    # a registered user's alert still gets through during the flood
    assert post(client, registered(client).incident().packet([client.authority])).status_code == 200


def test_unverified_still_needs_valid_crypto(client):
    import cbor2
    raw = Victim("Mallory").incident().packet([client.authority])
    a = cbor2.loads(raw)
    a[3] = bytes(64)  # forged signature
    assert post(client, cbor2.dumps(a)).status_code == 401


def test_kotlin_built_packet_accepted(client, tmp_path):
    """Cross-implementation: packet composed by the Kotlin core, opened by PyNaCl backend."""
    interop = ROOT / "core/build/interop"
    if not (interop / "packet.cbor").exists():
        pytest.skip("run ./gradlew :core:test first")
    keys = json.loads((interop / "keys.json").read_text())
    import main
    from nacl.public import PrivateKey
    main.authority_sk = PrivateKey(bytes.fromhex(keys["authority_sk"]))
    main.store.register(bytes.fromhex(keys["identity_pk"]), "Interop", "+911234567890", "dev-x")
    raw = (interop / "packet.cbor").read_bytes()
    r = main.process(raw, now_ms=__import__("cbor2").loads(__import__("cbor2").loads(raw)[1])["ts"])
    assert r["status"] == "dispatch"


def test_clear_dispatches_keeps_replay_protection(client):
    inc = registered(client).incident()
    p1 = inc.packet([client.authority])
    assert post(client, p1).status_code == 200
    assert len(client.get("/dispatches").json()) == 1
    r = client.delete("/dispatches")
    assert r.status_code == 200 and r.json()["deleted"] == 1
    assert client.get("/dispatches").json() == []
    # the same alert replayed after a clear is still recognised as already seen
    assert post(client, p1).status_code == 208
    assert client.get("/dispatches").json() == []
