"""Pydantic models for the frozen ``agent-output`` contract (SPEC §6, ADR-0007).

This module is the single server-side source of the ``agent-output`` shape. Its
emitted JSON Schema is checked for equality against
``contracts/schema/agent-output.v1.schema.json`` by ``app.contracts check``; the
contract file is frozen and authoritative, so these models exist to conform to it,
never the other way round.

Geometry is normalized ``[0,1]`` relative to the exported image (contract
``coordinate-mapping``). The schema itself carries no coordinate range; the range
check is a *semantic* pass applied after schema validation (see
``app.contracts.validation``).
"""

from __future__ import annotations

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, RootModel

# --- Primitive value types, forced into $defs as refs to match the frozen schema ---


class Point(RootModel[Annotated[list[float], Field(min_length=2, max_length=2)]]):
    pass


class Rect(RootModel[Annotated[list[float], Field(min_length=4, max_length=4)]]):
    pass


class Color(RootModel[Annotated[str, Field(pattern=r"^#[0-9A-Fa-f]{6}$")]]):
    pass


_Label = Annotated[str, Field(max_length=200)] | None
_Id = Annotated[str, Field(min_length=1, max_length=32)]


class _Base(BaseModel):
    model_config = ConfigDict(extra="forbid")


# --- Annotation vocabulary (closed set, v1) ---


class Highlight(_Base):
    id: _Id
    type: Literal["highlight"]
    points: Annotated[list[Point], Field(min_length=3)]
    color: Color | None = None
    label: _Label = None


class Arrow(_Base):
    id: _Id
    type: Literal["arrow"]
    from_: Point = Field(alias="from")
    to: Point
    color: Color | None = None
    label: _Label = None


class Ellipse(_Base):
    id: _Id
    type: Literal["ellipse"]
    center: Point
    rx: Annotated[float, Field(gt=0, le=1)]
    ry: Annotated[float, Field(gt=0, le=1)]
    color: Color | None = None
    label: _Label = None


class RectAnnotation(_Base):
    id: _Id
    type: Literal["rect"]
    x: float
    y: float
    w: Annotated[float, Field(gt=0, le=1)]
    h: Annotated[float, Field(gt=0, le=1)]
    color: Color | None = None
    label: _Label = None


class Underline(_Base):
    id: _Id
    type: Literal["underline"]
    points: Annotated[list[Point], Field(min_length=2)]
    color: Color | None = None
    label: _Label = None


class Strikethrough(_Base):
    id: _Id
    type: Literal["strikethrough"]
    points: Annotated[list[Point], Field(min_length=2)]
    color: Color | None = None
    label: _Label = None


class Path(_Base):
    id: _Id
    type: Literal["path"]
    points: Annotated[list[Point], Field(min_length=2)]
    closed: bool
    color: Color | None = None
    label: _Label = None


class Text(_Base):
    id: _Id
    type: Literal["text"]
    at: Point
    text: Annotated[str, Field(min_length=1, max_length=500)]
    size: Annotated[float, Field(gt=0, le=0.2)]
    color: Color | None = None
    label: _Label = None


class MarginNote(_Base):
    id: _Id
    type: Literal["margin_note"]
    y: float
    text: Annotated[str, Field(min_length=1, max_length=1000)]
    color: Color | None = None
    label: _Label = None


class Annotation(
    RootModel[
        Highlight
        | Arrow
        | Ellipse
        | RectAnnotation
        | Underline
        | Strikethrough
        | Path
        | Text
        | MarginNote
    ]
):
    pass


# --- Cards & brain writes ---


class Anchor(_Base):
    annotation_id: str | None = None
    region: Rect | None = None


class CardAction(_Base):
    id: _Id
    label: Annotated[str, Field(min_length=1, max_length=80)]
    kind: Literal["confirm", "reject", "run_tool", "open_canvas", "save_to_brain"]
    payload: dict


class Card(_Base):
    kind: Literal["answer", "task", "fact", "question", "action", "error"]
    title: Annotated[str, Field(min_length=1, max_length=120)]
    body: Annotated[str, Field(max_length=8000)]
    anchors: list[Anchor]
    actions: list[CardAction]


class BrainWrite(_Base):
    kind: Literal["fact", "task", "reference", "decision"]
    text: Annotated[str, Field(min_length=1, max_length=2000)]
    tags: list[Annotated[str, Field(max_length=40)]]
    source_region: Rect | None = None


class AgentOutput(_Base):
    summary: Annotated[str, Field(min_length=1, max_length=300)]
    annotations: list[Annotation]
    cards: list[Card]
    brain_writes: list[BrainWrite]
