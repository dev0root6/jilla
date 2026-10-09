"""Wire-format parsing and crypto checks for SOS packets (mirrors core/protocol).

Packet  = CBOR [ttl, header_bytes, sealed_bytes, signature]
signed  = header_bytes || sealed_bytes            (Ed25519, incident key)
sealed  = CBOR [[kid(8), crypto_box_seal(details)], ...]
details = CBOR {rid, bsig, aid, name, lat, lng, dev, note, contacts}
bsig    = Ed25519_identity("SOSMESH-BIND-v1" || incident_pub)
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass

import cbor2
from nacl.exceptions import BadSignatureError, CryptoError
from nacl.public import PrivateKey, SealedBox
from nacl.signing import VerifyKey

VERSION = 1
MAX_TTL = 15
MAX_PACKET_BYTES = 4096
MAX_RECIPIENTS = 8
MAX_AGE_MS = 6 * 60 * 60 * 1000
MAX_FUTURE_SKEW_MS = 5 * 60 * 1000
BINDING_TAG = b"SOSMESH-BIND-v1"
GEOHASH_RE = re.compile(r"^[0123456789bcdefghjkmnpqrstuvwxyz]{0,5}$")


class Reject(Exception):
    """Packet refused. `status` is the HTTP status the API returns."""

    def __init__(self, status: int, reason: str):
        super().__init__(reason)
        self.status = status
        self.reason = reason


@dataclass
class Header:
    version: int
    alert_id: bytes
    incident_pub: bytes
    timestamp_ms: int
    counter: int
    coarse_geohash: str


@dataclass
class Packet:
    ttl: int
    header_bytes: bytes
    sealed_bytes: bytes
    signature: bytes
    header: Header
    sealed: list[tuple[bytes, bytes]]


@dataclass
class Details:
    real_identity_pub: bytes
    binding_sig: bytes
    alert_id: bytes
    name: str
    lat: float
    lng: float
    device_uuid: str
    note: str
    contacts: list[str]


def key_id(pk: bytes) -> bytes:
    return hashlib.blake2b(pk, digest_size=8).digest()


def _loads(b: bytes, what: str):
    try:
        return cbor2.loads(b)
    except Exception as e:  # cbor2 raises several types on junk
        raise Reject(400, f"{what}: malformed CBOR ({e.__class__.__name__})")


def _typed(m: dict, k: str, t: type):
    v = m.get(k)
    if not isinstance(v, t) or (t is int and isinstance(v, bool)):
        raise Reject(400, f"missing/invalid field {k}")
    return v


def parse(raw: bytes) -> Packet:
    """Step 1: structural validation of the public parts."""
    if len(raw) > MAX_PACKET_BYTES:
        raise Reject(413, "packet too large")
    arr = _loads(raw, "packet")
    if not isinstance(arr, list) or len(arr) != 4:
        raise Reject(400, "packet must be a 4-element array")
    ttl, hb, sb, sig = arr
    if not isinstance(ttl, int) or not 0 <= ttl <= MAX_TTL:
        raise Reject(400, "ttl out of range")
    if not all(isinstance(x, bytes) for x in (hb, sb, sig)) or len(sig) != 64:
        raise Reject(400, "bad header/sealed/signature encoding")

    h = _loads(hb, "header")
    if not isinstance(h, dict):
        raise Reject(400, "header must be a map")
    header = Header(
        version=_typed(h, "v", int),
        alert_id=_typed(h, "aid", bytes),
        incident_pub=_typed(h, "ipk", bytes),
        timestamp_ms=_typed(h, "ts", int),
        counter=_typed(h, "ctr", int),
        coarse_geohash=_typed(h, "gh", str),
    )
    if header.version != VERSION:
        raise Reject(400, f"unsupported version {header.version}")
    if len(header.alert_id) != 16 or len(header.incident_pub) != 32:
        raise Reject(400, "bad alert_id/incident_pub length")
    if not 0 <= header.counter <= 0xFFFFFFFF or header.timestamp_ms < 0:
        raise Reject(400, "counter/timestamp out of range")
    if not GEOHASH_RE.match(header.coarse_geohash):
        raise Reject(400, "bad coarse geohash")

    s = _loads(sb, "sealed")
    if not isinstance(s, list) or not 1 <= len(s) <= MAX_RECIPIENTS:
        raise Reject(400, "bad sealed recipient list")
    sealed = []
    for e in s:
        if not (isinstance(e, list) and len(e) == 2 and all(isinstance(x, bytes) for x in e) and len(e[0]) == 8):
            raise Reject(400, "bad sealed entry")
        sealed.append((e[0], e[1]))
    return Packet(ttl, hb, sb, sig, header, sealed)


def verify_signature(p: Packet) -> None:
    """Step 2: Ed25519 over header||sealed with the incident key."""
    try:
        VerifyKey(p.header.incident_pub).verify(p.header_bytes + p.sealed_bytes, p.signature)
    except (BadSignatureError, CryptoError, ValueError):
        raise Reject(401, "incident signature invalid")


def check_freshness(p: Packet, now_ms: int) -> None:
    if p.header.timestamp_ms < now_ms - MAX_AGE_MS:
        raise Reject(422, "stale alert")
    if p.header.timestamp_ms > now_ms + MAX_FUTURE_SKEW_MS:
        raise Reject(422, "timestamp in the future")


def open_sealed(p: Packet, sk: PrivateKey) -> Details:
    """Step 4 + binding half of step 5. Registration is checked by the caller."""
    kid = key_id(bytes(sk.public_key))
    box = next((b for k, b in p.sealed if k == kid), None)
    if box is None:
        raise Reject(422, "no sealed copy for this authority")
    try:
        plain = SealedBox(sk).decrypt(box)
    except CryptoError:
        raise Reject(401, "sealed box authentication failed")
    d = _loads(plain, "details")
    if not isinstance(d, dict):
        raise Reject(400, "details must be a map")
    contacts = d.get("contacts")
    if not isinstance(contacts, list) or not all(isinstance(c, str) for c in contacts):
        raise Reject(400, "bad contacts")
    det = Details(
        real_identity_pub=_typed(d, "rid", bytes),
        binding_sig=_typed(d, "bsig", bytes),
        alert_id=_typed(d, "aid", bytes),
        name=_typed(d, "name", str),
        lat=_typed(d, "lat", float),
        lng=_typed(d, "lng", float),
        device_uuid=_typed(d, "dev", str),
        note=_typed(d, "note", str),
        contacts=contacts,
    )
    if det.alert_id != p.header.alert_id:
        raise Reject(401, "sealed alert_id does not match header")
    try:
        VerifyKey(det.real_identity_pub).verify(BINDING_TAG + p.header.incident_pub, det.binding_sig)
    except (BadSignatureError, CryptoError, ValueError):
        raise Reject(401, "binding signature invalid")
    return det
