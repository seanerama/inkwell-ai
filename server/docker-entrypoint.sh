#!/bin/sh
# Container command dispatch (ADR-0002): one image, two processes.
#   api    -> uvicorn (the HTTP API)
#   worker -> the Postgres queue worker
# Any other argv (alembic, inkwell, sh) is exec'd as-is, so the same image runs
# migrations and the token CLI.
set -e
case "$1" in
  api)
    exec uvicorn app.main:app --host 0.0.0.0 --port 8000 --proxy-headers
    ;;
  worker)
    exec python -m app.worker
    ;;
  *)
    exec "$@"
    ;;
esac
