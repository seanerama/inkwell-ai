# syntax=docker/dockerfile:1
# Multi-stage, non-root. Commands: `api` and `worker` (ADR-0002).
# Built multi-arch (linux/amd64 for mini-hp01, linux/arm64 for later promotion).
# BUILD CONTEXT IS THE REPO ROOT (needs contracts/):  docker build -f server/Dockerfile .

# ---- builder: resolve the frozen environment with uv ----
FROM python:3.12-slim AS builder
COPY --from=ghcr.io/astral-sh/uv:0.5 /uv /uvx /bin/
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never
WORKDIR /app
# Install only production dependencies against the committed lock, no project code yet.
COPY server/pyproject.toml server/uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-install-project --no-dev
# Now add the project itself.
COPY server/app ./app
COPY server/migrations ./migrations
COPY server/alembic.ini ./
# The frozen contracts the runtime loads (structured-output schema + fixtures).
COPY contracts/schema ./contracts/schema
COPY contracts/fixtures ./contracts/fixtures
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-dev

# ---- runtime ----
FROM python:3.12-slim AS runtime
# Version derived from the release tag (release.yml passes INKWELL_VERSION); /v1/health
# reports it. Local builds get the non-release marker.
ARG INKWELL_VERSION=0.0.0-dev
ENV INKWELL_VERSION=${INKWELL_VERSION} \
    INKWELL_CONTRACTS_DIR=/app/contracts
# Apply Debian security updates on top of the base image: the slim tag lags the
# security archive (perl-base, gzip, pcre2, sqlite fixes at v0.0.1), and the release
# Trivy gate fails on fixable CRITICAL/HIGH findings. No new packages are installed.
RUN apt-get update \
    && apt-get -y --no-install-recommends upgrade \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*
ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    INKWELL_BLOB_DIR=/data/blobs
WORKDIR /app
COPY --from=builder /app /app
COPY server/docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh \
    && groupadd --system inkwell \
    && useradd --system --gid inkwell --home-dir /app inkwell \
    && mkdir -p /data/blobs \
    && chown -R inkwell:inkwell /app /data
USER inkwell
EXPOSE 8000
ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["api"]
