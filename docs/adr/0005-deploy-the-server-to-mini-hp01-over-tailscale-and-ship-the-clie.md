# 0005. Deploy the server to mini-hp01 over Tailscale and ship the client via GitHub Releases

- **Status:** Accepted (operator may override before Stage 0 lands)
- **Date:** 2026-09-15

## Context

The global deployment catalog (`verity deployment list`) offers seven methods. The
server needs Docker, Postgres, a persistent volume, and to be reachable from one
Android tablet. The client needs a signed APK delivered to that tablet. Canvas images
are sensitive handwriting (SPEC §11), so a private network path is preferable to a
public endpoint. The tablet is a native Kotlin app, so the catalog's Expo/EAS method
does not apply.

## Decision

**Server → `mini-hp01-systemd`.** Docker Compose stacks under `/srv/inkwell/<env>/`
for two environments on the same host, `staging` and `prod`, each with its own
Postgres volume, blob volume, and `.env`. A systemd unit per environment runs
`docker compose up` and restarts on boot. TLS and hostname come from
`tailscale serve`: prod on `https://mini-hp01.taile0ffc4.ts.net:8443`, staging on
`:8444`. Port 443 is already taken on the host by an existing `tailscale serve`
route (`/` → `127.0.0.1:4317`), so Inkwell uses its own ports rather than sharing
443 by path. The tablet joins the tailnet with the Tailscale Android app; the server is
never exposed to the public internet. Deploys pull the tagged image from GHCR and
run `alembic upgrade head` before swapping containers.

**Client → new catalog method `gradle-github-releases`.** GitHub Actions builds a
signed release APK on every `v*` tag and attaches it to the GitHub Release; the
operator sideloads it. The signing keystore lives in GitHub Actions secrets as base64
(locations recorded in `.verity/deploy-access.md`). Same tag, same release, as the
server image.

**Promotion path (later, not v1):** `coolify` on `ec2-primary`, which is why the
server image is built for `linux/arm64` as well (ADR-0002) and why blobs sit behind
an interface (ADR-0004).

## Alternatives considered

- **`coolify` / `ec2-primary` now.** Public, ARM64, 3.7 GB RAM shared with several
  other stacks. Would expose the API publicly from day one and compete for memory
  with a Postgres and a Python worker. Better as the promotion target once the app
  is proven.
- **`nsaf-dev-server`.** Older host running legacy nohup services; the catalog names
  mini-hp01 as the current owner-selected target.
- **`cloudflare-workers-actions` / `cloudflare-pages`.** No Python runtime, no
  persistent volume, no long-running worker. Not a fit.
- **`eas-github-releases`.** Expo-only pipeline; the client is native Gradle.

## Consequences

- The operator must run `tailscale serve` once per environment on mini-hp01 and
  install Tailscale on the tablet. Both are recorded as Stage 0 setup steps.
- Tailscale SSH to mini-hp01 uses check mode: a fresh session is redirected to a
  Tailscale login URL once, after which non-interactive `ssh` works (verified
  2026-09-15). `deploy.sh` runs from the operator's workstation, not from CI.
- Host inspection 2026-09-15: x86_64, Docker 29.7 with Compose 5.5, Tailscale 1.102,
  12 CPUs, 15 GB RAM (8 GB available), 198 GB free. The operator account is **not**
  yet in the `docker` group and `/srv/inkwell` does not exist; both are Stage 0
  operator prerequisites.
- Staging and prod share a machine, so a runaway staging job can affect prod. Compose
  memory limits per stack mitigate this.
- The catalog method `gradle-github-releases` has been added to
  `~/.verity/deployment-methods.md`.
