"""The ``BlobStore`` interface (ADR-0004).

v1 ships ``LocalBlobStore``; an ``S3BlobStore`` is an additive later stage selected by
``BLOB_STORE=s3``. Object keys are already S3-safe.
"""

from __future__ import annotations

import abc


class BlobStore(abc.ABC):
    @abc.abstractmethod
    def put(self, key: str, data: bytes, mime: str) -> str:
        """Store ``data`` under ``key`` and return the key."""

    @abc.abstractmethod
    def open(self, key: str) -> bytes:
        """Return the bytes stored under ``key`` (raises KeyError if absent)."""

    @abc.abstractmethod
    def delete(self, key: str) -> None:
        """Remove ``key`` if present."""

    @abc.abstractmethod
    def signed_url(self, key: str, ttl: int = 300) -> str:
        """Return an authenticated, expiring ``GET /v1/blobs/{key}`` URL (ADR-0004)."""
