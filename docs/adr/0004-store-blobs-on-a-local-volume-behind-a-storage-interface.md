# 0004. Store blobs on a local volume behind a storage interface

- **Status:** Accepted
- **Date:** 2026-09-15

## Context

Blobs are canvas exports (PNG, capped at 2 MB), agent-pushed PDFs and images, and
future formalized canvases. SPEC §10.1 says "S3-compatible object storage" and §11
says blobs must be served through signed, expiring URLs and never from a public bucket.
The server runs on a private tailnet host with ample disk (ADR-0005).

## Decision

- A `BlobStore` interface (`put(key, bytes, mime) / open(key) / delete(key) / signed_url(key, ttl)`)
  with one implementation in v1: **`LocalBlobStore`** writing under a Docker volume
  (`/data/blobs/<env>/`), keys sharded by the first two characters.
- Blobs are served only by the API at `GET /v1/blobs/{key}`, which requires the device
  bearer token. The `url` returned by `POST /v1/blobs` is that authenticated route
  plus a short-lived HMAC query signature, so the URL alone is not a capability
  beyond its TTL. That satisfies "signed, expiring" without a bucket.
- The base64 `image` in a `POST /v1/jobs` request is persisted to the blob store on
  receipt; the stored `request` JSON keeps an `image_key`, never the base64. The wire
  shape (contract `device-api`) is unchanged.
- An `S3BlobStore` implementation is an additive later stage, selected by
  `BLOB_STORE=s3`. Object keys are already S3-safe.

## Alternatives considered

- **MinIO container.** Real S3 semantics locally, but another container, another
  credential pair, and pre-signed URLs pointing at a second hostname the tablet must
  reach over the tailnet.
- **Cloudflare R2.** Already in the operator's account and S3-compatible, but it moves
  sensitive handwriting off the private host for a single-user app, and the choice
  would be driven by the tool being installed, not by need.
- **Bytes in Postgres (`bytea`).** Simplest of all, but bloats backups and makes the
  future S3 move a data migration instead of a config flag.

## Consequences

- Backups are the Postgres dump plus the blob volume; the SRE recovery plan must cover
  both.
- Promotion to a host without a persistent volume requires enabling `S3BlobStore`
  first. That is a config change plus a one-off copy script, not a code change.
