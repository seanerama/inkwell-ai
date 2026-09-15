"""Blob storage behind a swappable interface (ADR-0004)."""

from app.blobs.base import BlobStore
from app.blobs.local import LocalBlobStore

__all__ = ["BlobStore", "LocalBlobStore", "get_blob_store"]

_store: BlobStore | None = None


def get_blob_store() -> BlobStore:
    """Return the process-wide blob store. Only LocalBlobStore exists in v1."""
    global _store
    if _store is None:
        from app.config import get_settings

        _store = LocalBlobStore(get_settings().blob_dir)
    return _store
