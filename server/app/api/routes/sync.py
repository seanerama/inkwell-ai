"""Sync route: monotonic cursor over jobs in both directions (contract device-api)."""

from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.api.deps import get_db, require_token
from app.api.schemas import SyncOut, job_to_out
from app.jobs import queue

router = APIRouter()


@router.get("/sync", response_model=SyncOut)
def sync(cursor: str | None = None, db: Session = Depends(get_db), _=Depends(require_token)):
    jobs, next_cursor = queue.sync_page(db, cursor)
    return SyncOut(jobs=[job_to_out(j) for j in jobs], cursor=next_cursor)
