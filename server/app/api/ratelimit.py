"""In-process per-token sliding-window rate limiter for ``POST /jobs`` (ADR-0008).

30 submissions per token per minute; the 31st in a window is rejected with ``429`` and
a ``Retry-After`` header. One process owns the queue (ADR-0002), so in-memory state is
sufficient for v1.
"""

from __future__ import annotations

import time
from collections import defaultdict, deque

WINDOW_SECONDS = 60.0


class RateLimiter:
    def __init__(self, limit_per_min: int) -> None:
        self.limit = limit_per_min
        self._hits: dict[str, deque[float]] = defaultdict(deque)

    def check(self, key: str) -> tuple[bool, int]:
        """Return (allowed, retry_after_seconds). Records the hit when allowed."""
        now = time.monotonic()
        hits = self._hits[key]
        while hits and now - hits[0] >= WINDOW_SECONDS:
            hits.popleft()
        if len(hits) >= self.limit:
            retry_after = max(1, int(WINDOW_SECONDS - (now - hits[0])) + 1)
            return False, retry_after
        hits.append(now)
        return True, 0

    def reset(self) -> None:
        self._hits.clear()
