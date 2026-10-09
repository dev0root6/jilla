"""Independent Python implementation of the packet builder (for tests and attack scripts).

Built only from the spec + PyNaCl, so agreement with the Kotlin app is a real interop check.
"""

from __future__ import annotations

import os
import time

import cbor2
from nacl.public import PublicKey, SealedBox
from nacl.signing import SigningKey

from sosproto import BINDING_TAG, key_id


def geohash(lat: float, lng: float, precision: int = 5) -> str:
    b32 = "0123456789bcdefghjkmnpqrstuvwxyz"
    lat_r, lng_r, out, bit, ch, even = [-90.0, 90.0], [-180.0, 180.0], "", 0, 0, True
    while len(out) < precision:
        r, v = (lng_r, lng) if even else (lat_r, lat)
        mid = (r[0] + r[1]) / 2
        if v >= mid:
            ch, r[0] = (ch << 1) | 1, mid
        else:
            ch, r[1] = ch << 1, mid
        even = not even
        bit += 1
        if bit == 5:
            out += b32[ch]
            bit, ch = 0, 0
    return out


class Victim:
    def __init__(self, name: str = "Asha"):
        self.identity = SigningKey.generate()
        self.name = name

    @property
    def pub_hex(self) -> str:
        return bytes(self.identity.verify_key).hex()

    def registration(self, phone: str = "+919876543210", device_id: str = "py-device", ts_ms: int | None = None) -> dict:
        from main import registration_message
        ts = ts_ms if ts_ms is not None else int(time.time() * 1000)
        pub = bytes(self.identity.verify_key)
        sig = self.identity.sign(registration_message(ts, phone, device_id, self.name, pub)).signature
        return {"identity_pub": pub.hex(), "name": self.name, "phone": phone,
                "device_id": device_id, "ts_ms": ts, "signature": sig.hex()}

    def incident(self) -> "Incident":
        return Incident(self)


class Incident:
    def __init__(self, victim: Victim):
        self.victim = victim
        self.alert_id = os.urandom(16)
        self.key = SigningKey.generate()
        self.counter = 0
        self.binding_sig = victim.identity.sign(BINDING_TAG + bytes(self.key.verify_key)).signature

    def packet(self, authorities: list[bytes], lat=12.97161, lng=77.59456, note="", ttl=7,
               ts_ms: int | None = None, counter: int | None = None) -> bytes:
        if counter is None:
            self.counter += 1
            counter = self.counter
        ipk = bytes(self.key.verify_key)
        header = cbor2.dumps({
            "v": 1, "aid": self.alert_id, "ipk": ipk,
            "ts": ts_ms if ts_ms is not None else int(time.time() * 1000),
            "ctr": counter, "gh": geohash(lat, lng),
        })
        details = cbor2.dumps({
            "rid": bytes(self.victim.identity.verify_key), "bsig": self.binding_sig,
            "aid": self.alert_id, "name": self.victim.name, "lat": float(lat), "lng": float(lng),
            "dev": "py-device", "note": note, "contacts": ["+910000000000"],
        })
        sealed = cbor2.dumps([[key_id(a), SealedBox(PublicKey(a)).encrypt(details)] for a in authorities])
        sig = self.key.sign(header + sealed).signature
        return cbor2.dumps([ttl, header, sealed, sig])
