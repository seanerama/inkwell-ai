"""Deterministic generator for the deploy-canary fixture (Stage 8).

Renders a 1109x1568 PNG of the note ``what is 1+9=?`` (black text on opaque white)
and writes it to ``server/app/canary/fixture.png``. It uses Pillow's bundled bitmap
font (``ImageFont.load_default()``) so it depends on no system font file, then scales
that bitmap up by an integer factor with a NEAREST resize so the note is large and
legible on the full-size canvas while staying byte-for-byte reproducible.

Pillow is a **dev-only** dependency: this script regenerates the committed PNG, but the
runtime CLI (``app/canary/run.py``) only reads the committed bytes and never imports it.

Regenerate the committed fixture with::

    cd server && uv run python scripts/make_canary_fixture.py
"""

from __future__ import annotations

import io
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

WIDTH = 1109
HEIGHT = 1568
TEXT = "what is 1+9=?"
WHITE = (255, 255, 255)
BLACK = (0, 0, 0)
# Horizontal margin (px, both sides) left blank around the scaled text.
SIDE_MARGIN = 200

OUTPUT = Path(__file__).resolve().parent.parent / "app" / "canary" / "fixture.png"


def render_png() -> bytes:
    """Return the PNG bytes for the canary note. Deterministic in a given environment."""
    font = ImageFont.load_default()

    # Measure the text with the bundled bitmap font.
    probe = ImageDraw.Draw(Image.new("RGB", (1, 1), WHITE))
    bbox = probe.textbbox((0, 0), TEXT, font=font)
    text_w = bbox[2] - bbox[0]
    text_h = bbox[3] - bbox[1]

    # Draw it once at native (tiny) size on a snug white tile.
    tile = Image.new("RGB", (text_w + 2, text_h + 2), WHITE)
    ImageDraw.Draw(tile).text((1 - bbox[0], 1 - bbox[1]), TEXT, font=font, fill=BLACK)

    # Scale up by an integer factor (NEAREST = deterministic, crisp pixels) so the note
    # fills most of the canvas width, then centre it on the full-size white canvas.
    scale = max(1, (WIDTH - SIDE_MARGIN) // tile.width)
    big = tile.resize((tile.width * scale, tile.height * scale), Image.NEAREST)

    canvas = Image.new("RGB", (WIDTH, HEIGHT), WHITE)
    canvas.paste(big, ((WIDTH - big.width) // 2, (HEIGHT - big.height) // 2))

    buf = io.BytesIO()
    canvas.save(buf, format="PNG")
    return buf.getvalue()


def main() -> None:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    data = render_png()
    OUTPUT.write_bytes(data)
    print(f"wrote {OUTPUT} ({len(data)} bytes)")


if __name__ == "__main__":
    main()
