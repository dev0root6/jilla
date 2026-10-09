"""Jilla authority stub: stands in for police/112 dispatch.

POST /register  {"identity_pub","name","phone","device_id","ts_ms","signature"}
                -> registers the user's long-term Ed25519 key. phone is the device's active SIM
                   number, or "" (then device_id identifies the user). The signature proves the
                   caller owns the key being registered.
GET  /          -> live authority dashboard (alerts + Google Maps)
POST /sos       raw CBOR (application/cbor) or {"packet": base64}
GET  /dispatches                                       -> dispatch log (for tests/demo)
GET  /authority                                        -> authority X25519 public key

Run:  .venv/bin/uvicorn main:app --host 0.0.0.0 --port 8000
"""

from __future__ import annotations

import base64
import binascii
import hmac
import json
import logging
import os
import sqlite3
import threading
import time
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse
from nacl.exceptions import BadSignatureError
from nacl.public import PrivateKey
from nacl.signing import VerifyKey
from pydantic import BaseModel

import sosproto
from sosproto import Reject

HERE = Path(__file__).resolve().parent
KEY_DIR = Path(os.environ.get("JILLA_KEY_DIR", HERE / "keys"))
DB_PATH = os.environ.get("JILLA_DB", str(HERE / "jilla.db"))
# When set, the dashboard and /dispatches (decrypted PII) require ?token=... . Phone-facing
# endpoints (/sos, /register, /authority) stay open: they only ever carry signed/sealed data.
DASH_TOKEN = os.environ.get("JILLA_DASH_TOKEN", "")
REG_TAG = b"JILLA-REG-v2"
# Alerts from identities that never registered are still dispatched (a real victim may never
# have been online), but flagged UNVERIFIED. Anyone can mint such alerts, so cap how many new
# unverified incidents per minute reach dispatch, so a flood can't bury real ones.
UNVERIFIED_NEW_PER_MIN = int(os.environ.get("JILLA_UNVERIFIED_PER_MIN", "10"))
unverified_recent: list[float] = []
unverified_lock = threading.Lock()


def allow_unverified_incident() -> bool:
    now = time.time()
    with unverified_lock:
        unverified_recent[:] = [t for t in unverified_recent if now - t < 60]
        if len(unverified_recent) >= UNVERIFIED_NEW_PER_MIN:
            return False
        unverified_recent.append(now)
        return True
REG_MAX_SKEW_MS = 10 * 60 * 1000
PHONE_RE = __import__("re").compile(r"^(\+?[0-9]{8,15})?$")

log = logging.getLogger("jilla.authority")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")


def load_or_create_key() -> PrivateKey:
    KEY_DIR.mkdir(parents=True, exist_ok=True)
    sk_file = KEY_DIR / "authority.key"
    if sk_file.exists():
        sk = PrivateKey(bytes.fromhex(sk_file.read_text().strip()))
    else:
        sk = PrivateKey.generate()
        sk_file.write_text(bytes(sk).hex())
        sk_file.chmod(0o600)
    (KEY_DIR / "authority.pub").write_text(bytes(sk.public_key).hex())
    return sk


class Store:
    """SQLite: registered users, per-alert replay state, dispatch log."""

    def __init__(self, path: str):
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.lock = threading.Lock()
        self.db.executescript(
            """
            CREATE TABLE IF NOT EXISTS users(
                identity_pub TEXT PRIMARY KEY, name TEXT, phone TEXT, device_id TEXT, registered_at REAL);
            CREATE TABLE IF NOT EXISTS alerts(
                alert_id TEXT PRIMARY KEY, incident_pub TEXT NOT NULL,
                identity_pub TEXT NOT NULL, max_counter INTEGER NOT NULL, first_seen REAL);
            CREATE TABLE IF NOT EXISTS dispatches(
                id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, record TEXT, at REAL);
            """
        )

    def register(self, pub: bytes, name: str, phone: str = "", device_id: str = "") -> None:
        with self.lock, self.db:
            self.db.execute(
                "INSERT OR REPLACE INTO users VALUES(?,?,?,?,?)", (pub.hex(), name, phone, device_id, time.time())
            )

    def user(self, pub: bytes):
        with self.lock:
            return self.db.execute(
                "SELECT name, phone, device_id FROM users WHERE identity_pub=?", (pub.hex(),)
            ).fetchone()

    def is_registered(self, pub: bytes) -> bool:
        with self.lock:
            return self.db.execute("SELECT 1 FROM users WHERE identity_pub=?", (pub.hex(),)).fetchone() is not None

    def alert_state(self, alert_id: bytes):
        with self.lock:
            return self.db.execute(
                "SELECT incident_pub, max_counter FROM alerts WHERE alert_id=?", (alert_id.hex(),)
            ).fetchone()

    def record(self, p: sosproto.Packet, d: sosproto.Details, kind: str, rec: dict) -> bool:
        """Atomically advance the alert's counter. False if another request won the race."""
        h = p.header
        with self.lock, self.db:
            row = self.db.execute(
                "SELECT incident_pub, max_counter FROM alerts WHERE alert_id=?", (h.alert_id.hex(),)
            ).fetchone()
            if row is None:
                self.db.execute(
                    "INSERT INTO alerts VALUES(?,?,?,?,?)",
                    (h.alert_id.hex(), h.incident_pub.hex(), d.real_identity_pub.hex(), h.counter, time.time()),
                )
            elif row[0] != h.incident_pub.hex() or h.counter <= row[1]:
                return False
            else:
                self.db.execute(
                    "UPDATE alerts SET max_counter=? WHERE alert_id=?", (h.counter, h.alert_id.hex())
                )
            self.db.execute(
                "INSERT INTO dispatches(kind, record, at) VALUES(?,?,?)", (kind, json.dumps(rec), time.time())
            )
            return True

    def clear_dispatches(self) -> int:
        """Deletes the dispatch log only. Per-alert replay state (alerts table) is kept on
        purpose, so a captured old alert replayed after a clear is still rejected."""
        with self.lock, self.db:
            return self.db.execute("DELETE FROM dispatches").rowcount

    def dispatches(self) -> list[dict]:
        with self.lock:
            rows = self.db.execute("SELECT kind, record, at FROM dispatches ORDER BY id").fetchall()
        return [{"kind": k, "at": at, **json.loads(r)} for k, r, at in rows]


authority_sk: PrivateKey
store: Store


@asynccontextmanager
async def lifespan(_: FastAPI):
    global authority_sk, store
    authority_sk = load_or_create_key()
    store = Store(DB_PATH)
    pub = bytes(authority_sk.public_key).hex()
    log.info("AUTHORITY X25519 PUBLIC KEY: %s", pub)
    log.info("(written to %s; the app build bundles it)", KEY_DIR / "authority.pub")
    yield


app = FastAPI(title="Jilla authority stub", lifespan=lifespan)


def parse_pub(h: str) -> bytes:
    try:
        pub = bytes.fromhex(h)
    except ValueError:
        raise HTTPException(400, "identity_pub must be hex")
    if len(pub) != 32:
        raise HTTPException(400, "identity_pub must be 32 bytes")
    return pub


def norm_phone(p: str) -> str:
    p = p.replace(" ", "").replace("-", "")
    if not PHONE_RE.match(p):
        raise HTTPException(400, "invalid phone number")
    return p


def registration_message(ts_ms: int, phone: str, device_id: str, name: str, pub: bytes) -> bytes:
    """Must match RegistrationClient.kt byte for byte."""
    return REG_TAG + "\x1f".join([str(ts_ms), phone, device_id, name]).encode() + b"\x1f" + pub


class Registration(BaseModel):
    identity_pub: str
    name: str
    phone: str = ""
    device_id: str
    ts_ms: int
    signature: str


@app.post("/register")
def register(r: Registration):
    """Register with the device's active number (or device id when there is no SIM number).

    No OTP in this POC: the number is read from the SIM by the OS. The Ed25519 signature
    proves possession of the identity key, and the timestamp bounds replay of the request.
    """
    pub = parse_pub(r.identity_pub)
    phone = norm_phone(r.phone)
    name = r.name.strip()[:64]
    device_id = r.device_id.strip()[:64]
    if not name or not device_id:
        raise HTTPException(400, "name and device_id required")
    if abs(int(time.time() * 1000) - r.ts_ms) > REG_MAX_SKEW_MS:
        raise HTTPException(401, "registration timestamp too old or in the future")
    try:
        VerifyKey(pub).verify(registration_message(r.ts_ms, phone, device_id, name, pub), bytes.fromhex(r.signature))
    except (BadSignatureError, ValueError):
        raise HTTPException(401, "identity key signature invalid")
    store.register(pub, name, phone, device_id)
    log.info("REGISTER identity=%s name=%r phone=%s device=%s (key possession verified)",
             pub.hex()[:16], name, phone or "(none, using device id)", device_id[:8])
    return {"status": "registered", "identified_by": "phone" if phone else "device_id"}


@app.get("/authority")
def authority():
    return {"x25519_pub": bytes(authority_sk.public_key).hex()}


def require_token(req: Request) -> None:
    if DASH_TOKEN and not hmac.compare_digest(req.query_params.get("token", ""), DASH_TOKEN):
        raise HTTPException(401, "dashboard token required: open /?token=<JILLA_DASH_TOKEN>")


@app.get("/dispatches")
def dispatches(req: Request):
    require_token(req)
    return store.dispatches()


@app.delete("/dispatches")
def clear_dispatches(req: Request):
    require_token(req)
    n = store.clear_dispatches()
    log.info("CLEARED %d dispatch record(s) from the console (replay protection kept)", n)
    return {"status": "cleared", "deleted": n}


@app.get("/", response_class=HTMLResponse)
def dashboard(req: Request):
    require_token(req)
    return (HERE / "dashboard.html").read_text()


async def read_packet(req: Request) -> bytes:
    body = await req.body()
    if req.headers.get("content-type", "").startswith("application/json"):
        try:
            return base64.b64decode(json.loads(body)["packet"], validate=True)
        except (ValueError, KeyError, TypeError, binascii.Error):
            raise Reject(400, "JSON body must be {\"packet\": base64}")
    return body


@app.post("/sos")
async def sos(req: Request):
    try:
        raw = await read_packet(req)
        return process(raw, int(time.time() * 1000))
    except Reject as r:
        log.warning("REJECT %s: %s", r.status, r.reason)
        return JSONResponse({"status": "rejected", "reason": r.reason}, status_code=r.status)


def process(raw: bytes, now_ms: int):
    # 1. parse public header
    p = sosproto.parse(raw)
    h = p.header
    aid = h.alert_id.hex()
    # 2. outer signature (cheap relay-level integrity)
    sosproto.verify_signature(p)
    # 3. dedup / replay by alert_id. State is only created after full
    #    validation, so a forged copy cannot squat on a genuine alert_id.
    state = store.alert_state(h.alert_id)
    if state is not None:
        if state[0] != h.incident_pub.hex():
            raise Reject(409, "alert_id bound to a different incident key")
        if h.counter <= state[1]:
            log.info("DUPLICATE alert=%s ctr=%d (max seen %d) ttl=%d", aid[:8], h.counter, state[1], p.ttl)
            return JSONResponse({"status": "duplicate"}, status_code=208)
    sosproto.check_freshness(p, now_ms)
    # 4. open sealed block, 5. binding signature + registered identity
    d = sosproto.open_sealed(p, authority_sk)
    user = store.user(d.real_identity_pub)
    verified = user is not None
    if not verified and state is None and not allow_unverified_incident():
        raise Reject(429, "too many unverified alerts right now")
    # 6. dispatch
    kind = "DISPATCH" if state is None else "UPDATE"
    rec = {
        "alert_id": aid,
        "counter": h.counter,
        "name": d.name,
        "verified": verified,
        "registered_name": user[0] if verified else None,
        "registered_phone": (user[1] or f"(no SIM number; device {user[2]})") if verified
        else "UNVERIFIED: sender never registered",
        "lat": d.lat,
        "lng": d.lng,
        "device_uuid": d.device_uuid,
        "note": d.note,
        "maps": f"https://maps.google.com/?q={d.lat},{d.lng}",
        "contacts": d.contacts,
        "identity": d.real_identity_pub.hex(),
        "sent_at_ms": h.timestamp_ms,
        "coarse_area": h.coarse_geohash,
        "ttl_at_arrival": p.ttl,
    }
    if not store.record(p, d, kind, rec):
        return JSONResponse({"status": "duplicate"}, status_code=208)
    log.info("%s%s %s", kind, "" if verified else " UNVERIFIED", json.dumps(rec))
    log.info("%s%s -> notify police/112 + contacts %s (simulated)", kind, "" if verified else " (UNVERIFIED)", d.contacts)
    return {"status": kind.lower(), "verified": verified, "alert_id": aid, "counter": h.counter}
