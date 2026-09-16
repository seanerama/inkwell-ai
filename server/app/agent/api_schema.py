"""Project the frozen ``agent-output`` JSON Schema onto the subset the Anthropic
structured-outputs API accepts (ADR-0006).

The contract file stays the schema of record and is never edited. The API supports
types, ``enum``/``const``/``anyOf``/``$ref``/``$defs``, ``required`` and
``additionalProperties: false``, but rejects numeric constraints, string length
constraints and "complex" array constraints — the first real call on staging failed
with ``400: For 'array' type, 'minItems' values other than 0 and 1 are not
supported``. Everything stripped here is still enforced after the call by the Pydantic
model (which the contract check proves equals the frozen schema) and by the semantic
validator, so the guarantee to consumers is unchanged; only what the model is
constrained to at generation time is looser.
"""

from __future__ import annotations

import copy
from typing import Any

# Keywords the API does not accept, removed wherever they appear.
_STRIPPED_KEYWORDS = frozenset(
    {
        "minLength",
        "maxLength",
        "pattern",
        "minimum",
        "maximum",
        "exclusiveMinimum",
        "exclusiveMaximum",
        "multipleOf",
        "maxItems",
        "uniqueItems",
        "minContains",
        "maxContains",
        # Prose is for humans and the prompt already describes the shape; it only
        # inflates the compiled grammar.
        "description",
        "title",
    }
)

# The nine annotation variants (contract agent-output §Annotation types). The API's
# constrained decoder rejected the nine-way ``anyOf`` as "compiled grammar too large"
# (third staging 400), so the projection flattens them into ONE closed object with a
# ``type`` enum and the union of the variants' fields, all optional except id/type.
# Which fields each type requires is enforced after the call by the Pydantic model.
_ANNOTATION_VARIANTS = (
    "Highlight",
    "Arrow",
    "Ellipse",
    "RectAnnotation",
    "Underline",
    "Strikethrough",
    "Path",
    "Text",
    "MarginNote",
)
# Top-level metadata the API has no use for.
_STRIPPED_TOP_LEVEL = frozenset({"$schema", "$id"})


def _strip(node: Any) -> Any:
    if isinstance(node, dict):
        out: dict[str, Any] = {}
        for key, value in node.items():
            if key in _STRIPPED_KEYWORDS:
                continue
            if key == "minItems" and value not in (0, 1):
                continue
            out[key] = _strip(value)
        # The API requires every object to say additionalProperties: false explicitly
        # (second staging 400). The contract's one open object, CardAction.payload,
        # is therefore constrained to {} at generation time; the contract still allows
        # any payload after the fact.
        if out.get("type") == "object" and "additionalProperties" not in out:
            out["additionalProperties"] = False
        return out
    if isinstance(node, list):
        return [_strip(item) for item in node]
    return node


def _flatten_annotation(defs: dict[str, Any]) -> None:
    """Replace ``$defs/Annotation`` (an anyOf over the variants) with one flat object and
    drop the now-unreferenced variant definitions. No-op if the shape is unexpected."""
    if not all(name in defs for name in _ANNOTATION_VARIANTS) or "anyOf" not in defs.get(
        "Annotation", {}
    ):
        return
    merged: dict[str, Any] = {}
    types: list[str] = []
    for name in _ANNOTATION_VARIANTS:
        variant = defs[name]
        for key, value in variant.get("properties", {}).items():
            if key == "type":
                types.append(value["const"])
            else:
                merged.setdefault(key, value)
    ordered = {"id": merged.pop("id"), "type": {"enum": types}}
    ordered.update(merged)
    defs["Annotation"] = {
        "type": "object",
        "additionalProperties": False,
        "required": ["id", "type"],
        "properties": ordered,
    }
    for name in _ANNOTATION_VARIANTS:
        defs.pop(name, None)


def for_structured_output(schema: dict[str, Any]) -> dict[str, Any]:
    """Return a deep-copied projection of ``schema`` safe to send as
    ``output_config.format.schema``. The input is not modified."""
    projected = _strip(copy.deepcopy(schema))
    for key in _STRIPPED_TOP_LEVEL:
        projected.pop(key, None)
    if isinstance(projected.get("$defs"), dict):
        _flatten_annotation(projected["$defs"])
    return projected


def unsupported_keywords(schema: dict[str, Any]) -> set[str]:
    """Keywords in ``schema`` the API would reject (used by tests and the contract check)."""
    found: set[str] = set()

    def walk(node: Any) -> None:
        if isinstance(node, dict):
            for key, value in node.items():
                if key in _STRIPPED_KEYWORDS or (key == "minItems" and value not in (0, 1)):
                    found.add(key)
                walk(value)
        elif isinstance(node, list):
            for item in node:
                walk(item)

    walk(schema)
    return found


def open_objects(schema: dict[str, Any]) -> list[str]:
    """JSON paths of objects without an explicit ``additionalProperties: false``."""
    found: list[str] = []

    def walk(node: Any, path: str) -> None:
        if isinstance(node, dict):
            if node.get("type") == "object" and node.get("additionalProperties") is not False:
                found.append(path or "$")
            for key, value in node.items():
                walk(value, f"{path}/{key}")
        elif isinstance(node, list):
            for i, item in enumerate(node):
                walk(item, f"{path}/{i}")

    walk(schema, "")
    return found
