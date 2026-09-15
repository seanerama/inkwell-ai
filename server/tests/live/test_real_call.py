"""Live end-to-end agent test — NOT a CI gate.

Runs only when ``INKWELL_LIVE_TESTS=1`` and ``ANTHROPIC_API_KEY`` are both set. It posts
a generated three-box PNG and asserts the agent returns a single valid highlight whose
bounding-box centre lands in the middle third of the image (i.e. it marked the middle
box). Run by the Tester before Stage 6.
"""

from __future__ import annotations

import os
import struct
import zlib

import pytest

from app.agent import client as agent_client
from app.agent.validate import run_agent

LIVE = os.environ.get("INKWELL_LIVE_TESTS") == "1" and bool(os.environ.get("ANTHROPIC_API_KEY"))

pytestmark = pytest.mark.skipif(
    not LIVE, reason="live test: set INKWELL_LIVE_TESTS=1 and ANTHROPIC_API_KEY"
)


def _three_box_png(width: int = 900, height: int = 300) -> bytes:
    """White canvas with three black boxes in a row (stdlib-only PNG encoder)."""
    white = bytearray([255, 255, 255] * width * height)

    def fill(x0: int, y0: int, x1: int, y1: int) -> None:
        for y in range(y0, y1):
            base = (y * width + x0) * 3
            for _ in range(x0, x1):
                white[base] = white[base + 1] = white[base + 2] = 0
                base += 3

    fill(50, 100, 250, 200)  # left box
    fill(350, 100, 550, 200)  # middle box (centre x ~ 0.5)
    fill(650, 100, 850, 200)  # right box

    # Assemble PNG: signature + IHDR + IDAT + IEND.
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter type 0
        raw.extend(white[y * width * 3 : (y + 1) * width * 3])

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )


def test_real_call_highlights_middle_box():
    import base64

    agent_client.set_client(None)  # use the real Anthropic client
    image_b64 = base64.standard_b64encode(_three_box_png()).decode("ascii")

    run = run_agent(
        model="claude-sonnet-5",
        system_prompt="",
        brain_context="",
        image_b64=image_b64,
        instruction="There are three black boxes in a row. Highlight only the middle box.",
        job_type="canvas.annotate",
        job_id="live-1",
    )

    highlights = [a.root for a in run.output.annotations if a.root.type == "highlight"]
    assert len(highlights) == 1, f"expected one highlight, got {len(run.output.annotations)}"

    xs = [p.root[0] for p in highlights[0].points]
    ys = [p.root[1] for p in highlights[0].points]
    cx = sum(xs) / len(xs)
    cy = sum(ys) / len(ys)
    assert 1 / 3 <= cx <= 2 / 3, f"highlight centre x={cx} not in middle third"
    assert 0.0 <= cy <= 1.0
