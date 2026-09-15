"""Semantic validation of an ``agent-output`` object, applied after schema validation.

Two tiers (contracts/fixtures/README.md):
  1. JSON Schema — enforced by the Pydantic models in ``app.schemas``.
  2. Semantic — coordinate range (contract ``coordinate-mapping``), annotation-id
     uniqueness, and anchor referential integrity — enforced here.

On success this returns the validated ``AgentOutput`` with coordinates clamped to
``[0,1]``; values inside the tolerance band ``[-0.05, 1.05]`` are clamped, values
outside it raise ``SemanticError``.
"""

from __future__ import annotations

from app.schemas import AgentOutput

TOLERANCE_LO = -0.05
TOLERANCE_HI = 1.05


class SemanticError(ValueError):
    """A response is schema-valid but violates a semantic rule."""

    def __init__(self, message: str, path: str | None = None) -> None:
        super().__init__(message)
        self.path = path


class _Clamps:
    """A tiny counter threaded through the clamp pass (contract coordinate-mapping)."""

    def __init__(self) -> None:
        self.count = 0


def _clamp(value: float, path: str, clamps: _Clamps) -> float:
    if value < TOLERANCE_LO or value > TOLERANCE_HI:
        raise SemanticError(
            f"coordinate outside [{TOLERANCE_LO}, {TOLERANCE_HI}] at {path}: {value}",
            path=path,
        )
    if value < 0.0 or value > 1.0:
        # Inside the tolerance band but outside [0,1]: clamped and counted (SPEC §5.5).
        clamps.count += 1
        return min(1.0, max(0.0, value))
    return value


def _clamp_point(pt: list[float], path: str, clamps: _Clamps) -> list[float]:
    return [_clamp(pt[0], f"{path}[0]", clamps), _clamp(pt[1], f"{path}[1]", clamps)]


def _clamp_rect(rect: list[float], path: str, clamps: _Clamps) -> list[float]:
    return [_clamp(rect[i], f"{path}[{i}]", clamps) for i in range(4)]


def validate_agent_output_counted(data: dict) -> tuple[AgentOutput, int]:
    """Validate ``data`` and return the clamped model plus the ``coordinate_clamps`` count.

    Raises ``pydantic.ValidationError`` on schema failure and ``SemanticError`` on a
    semantic failure (its ``.path`` names the offending coordinate, e.g.
    ``annotations[3].points[1][0]``). ``_reject_reason`` (fixture documentation) must be
    stripped by the caller; it would otherwise trip ``additionalProperties: false``.
    """
    ao = AgentOutput.model_validate(data)
    dumped = ao.model_dump(by_alias=True)
    clamps = _Clamps()

    # Tier 2a: coordinate range + clamp.
    for i, ann in enumerate(dumped["annotations"]):
        base = f"annotations[{i}]"
        t = ann["type"]
        if t in ("highlight", "underline", "strikethrough", "path"):
            ann["points"] = [
                _clamp_point(p, f"{base}.points[{j}]", clamps) for j, p in enumerate(ann["points"])
            ]
        elif t == "arrow":
            ann["from"] = _clamp_point(ann["from"], f"{base}.from", clamps)
            ann["to"] = _clamp_point(ann["to"], f"{base}.to", clamps)
        elif t == "ellipse":
            ann["center"] = _clamp_point(ann["center"], f"{base}.center", clamps)
            ann["rx"] = _clamp(ann["rx"], f"{base}.rx", clamps)
            ann["ry"] = _clamp(ann["ry"], f"{base}.ry", clamps)
        elif t == "rect":
            ann["x"] = _clamp(ann["x"], f"{base}.x", clamps)
            ann["y"] = _clamp(ann["y"], f"{base}.y", clamps)
            ann["w"] = _clamp(ann["w"], f"{base}.w", clamps)
            ann["h"] = _clamp(ann["h"], f"{base}.h", clamps)
        elif t == "text":
            ann["at"] = _clamp_point(ann["at"], f"{base}.at", clamps)
        elif t == "margin_note":
            ann["y"] = _clamp(ann["y"], f"{base}.y", clamps)

    for i, card in enumerate(dumped["cards"]):
        for j, anchor in enumerate(card.get("anchors", [])):
            if anchor.get("region") is not None:
                anchor["region"] = _clamp_rect(
                    anchor["region"], f"cards[{i}].anchors[{j}].region", clamps
                )
    for i, bw in enumerate(dumped["brain_writes"]):
        if bw.get("source_region") is not None:
            bw["source_region"] = _clamp_rect(
                bw["source_region"], f"brain_writes[{i}].source_region", clamps
            )

    # Tier 2b: annotation ids unique within a response.
    ids = [ann["id"] for ann in dumped["annotations"]]
    if len(ids) != len(set(ids)):
        raise SemanticError("annotation ids must be unique within a response")

    # Tier 2c: anchor.annotation_id must reference an id in the same response.
    known = set(ids)
    for i, card in enumerate(dumped["cards"]):
        for j, anchor in enumerate(card.get("anchors", [])):
            ref = anchor.get("annotation_id")
            if ref is not None and ref not in known:
                raise SemanticError(
                    f"cards[{i}].anchors[{j}].annotation_id '{ref}' has no matching annotation",
                    path=f"cards[{i}].anchors[{j}].annotation_id",
                )

    return AgentOutput.model_validate(dumped), clamps.count


def validate_agent_output(data: dict) -> AgentOutput:
    """Validate ``data`` against the schema and semantic rules; return a clamped model.

    Backwards-compatible wrapper over :func:`validate_agent_output_counted` for callers
    that do not need the clamp count (e.g. ``app.contracts check``).
    """
    ao, _ = validate_agent_output_counted(data)
    return ao
