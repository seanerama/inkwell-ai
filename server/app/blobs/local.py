"""``LocalBlobStore``: bytes on a local volume, keys sharded by first two chars.

Serving is done only by the API's ``GET /v1/blobs/{key}`` route, which requires the
device bearer token; ``signed_url`` appends a short-lived HMAC so the URL alone is not
a capability beyond its TTL (ADR-0004, SPEC §11).
"""

from __future__ import annotations

import hashlib
import hmac
import time
from pathlib import Path

from app.config import get_settings


class LocalBlobStore:
    def __init__(self, root: str) -> None:
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)

    def _path(self, key: str) -> Path:
        shard = key[:2] if len(key) >= 2 else "__"
        return self.root / shard / key

    def put(self, key: str, data: bytes, mime: str) -> str:
        path = self._path(key)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return key

    def open(self, key: str) -> bytes:
        path = self._path(key)
        if not path.exists():
            raise KeyError(key)
        return path.read_bytes()

    def delete(self, key: str) -> None:
        path = self._path(key)
        if path.exists():
            path.unlink()

    def sign(self, key: str, exp: int) -> str:
        secret = get_settings().blob_signing_key.encode()
        return hmac.new(secret, f"{key}:{exp}".encode(), hashlib.sha256).hexdigest()

    def verify(self, key: str, exp: int, sig: str) -> bool:
        if exp < int(time.time()):
            return False
        return hmac.compare_digest(self.sign(key, exp), sig)

    def signed_url(self, key: str, ttl: int = 300) -> str:
        exp = int(time.time()) + ttl
        sig = self.sign(key, exp)
        return f"/v1/blobs/{key}?sig={sig}&exp={exp}"
