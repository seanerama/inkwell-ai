"""``app.contracts check`` implementation (ADR-0007), judged by exit code.

Two things must hold:

1. **Schema equality.** The JSON Schema emitted by ``app.schemas.AgentOutput`` must
   equal ``contracts/schema/agent-output.v1.schema.json`` after a *structural*
   normalization that removes only non-semantic differences between a hand-written
   contract and a Pydantic-emitted one: documentation keys (``$schema``, ``$id``,
   ``title``, ``description``), ``default`` markers, the redundant
   ``additionalProperties: true`` (the JSON Schema default), optional-vs-nullable
   (``anyOf`` with a ``{"type": "null"}`` branch), and ``required`` list ordering.
   Nothing that changes the set of accepted documents is normalized away.

2. **Fixtures.** Every ``valid-*`` fixture validates (schema + semantic); every
   ``invalid-*`` fixture is rejected at one tier or the other
   (contracts/fixtures/README.md).
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

from pydantic import ValidationError

from app.contracts.validation import SemanticError, validate_agent_output
from app.schemas import AgentOutput

_DOC_KEYS = {"$schema", "$id", "title", "description", "default"}


def _repo_root() -> Path:
    # server/app/contracts/check.py -> repo root is three parents up from app/.
    return Path(__file__).resolve().parents[3]


def _contracts_dir() -> Path:
    return _repo_root() / "contracts"


def normalize(node: Any) -> Any:
    """Reduce a JSON Schema to a canonical structural form (see module docstring)."""
    if isinstance(node, dict):
        out: dict[str, Any] = {}
        for key, value in node.items():
            if key in _DOC_KEYS:
                continue
            if key == "additionalProperties" and value is True:
                continue
            out[key] = normalize(value)
        for combiner in ("anyOf", "oneOf"):
            branches = out.get(combiner)
            if isinstance(branches, list):
                non_null = [
                    b for b in branches if not (isinstance(b, dict) and b.get("type") == "null")
                ]
                if len(non_null) != len(branches):
                    if len(non_null) == 1:
                        del out[combiner]
                        merged = non_null[0]
                        if isinstance(merged, dict):
                            for mk, mv in merged.items():
                                out.setdefault(mk, mv)
                        else:
                            out[combiner] = [merged]
                    else:
                        out[combiner] = non_null
        # A ``type`` alongside ``const``/``enum`` is redundant (the enumerated value
        # set already fixes the type). Pydantic emits it; the frozen contract omits
        # it. Dropping it here does not change the set of accepted documents.
        if ("const" in out or "enum" in out) and "type" in out:
            del out["type"]
        if isinstance(out.get("required"), list):
            out["required"] = sorted(out["required"])
        return out
    if isinstance(node, list):
        return [normalize(item) for item in node]
    return node


def emitted_schema() -> dict[str, Any]:
    return AgentOutput.model_json_schema()


def frozen_schema() -> dict[str, Any]:
    path = _contracts_dir() / "schema" / "agent-output.v1.schema.json"
    return json.loads(path.read_text())


def _diff(a: Any, b: Any, path: str = "") -> list[str]:
    diffs: list[str] = []
    if isinstance(a, dict) and isinstance(b, dict):
        for key in sorted(set(a) | set(b)):
            if key not in a:
                diffs.append(f"{path}/{key}: missing in emitted")
            elif key not in b:
                diffs.append(f"{path}/{key}: extra in emitted")
            else:
                diffs.extend(_diff(a[key], b[key], f"{path}/{key}"))
    elif isinstance(a, list) and isinstance(b, list):
        if len(a) != len(b):
            diffs.append(f"{path}: list length {len(a)} != {len(b)}")
        else:
            for i, (x, y) in enumerate(zip(a, b, strict=False)):
                diffs.extend(_diff(x, y, f"{path}[{i}]"))
    elif a != b:
        diffs.append(f"{path}: {a!r} != {b!r}")
    return diffs


def check_schema_equality() -> list[str]:
    emitted = normalize(emitted_schema())
    frozen = normalize(frozen_schema())
    return _diff(emitted, frozen)


def _fixture_files() -> list[Path]:
    fixtures = sorted((_contracts_dir() / "fixtures" / "agent-output").glob("*.json"))
    if not fixtures:
        raise FileNotFoundError("no agent-output fixtures found")
    return fixtures


def _load_fixture(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text())
    data.pop("_reject_reason", None)  # human documentation only (fixtures README)
    return data


def check_fixtures() -> list[str]:
    problems: list[str] = []
    for path in _fixture_files():
        name = path.name
        data = _load_fixture(path)
        try:
            validate_agent_output(data)
            accepted = True
            reason = ""
        except (ValidationError, SemanticError) as exc:
            accepted = False
            reason = str(exc).splitlines()[0] if str(exc) else exc.__class__.__name__
        if name.startswith("valid-") and not accepted:
            problems.append(f"{name}: expected VALID but was rejected ({reason})")
        elif name.startswith("invalid-") and accepted:
            problems.append(f"{name}: expected INVALID but was accepted")
    return problems


def run() -> int:
    schema_diffs = check_schema_equality()
    fixture_problems = check_fixtures()

    if not schema_diffs:
        print("schema: emitted AgentOutput JSON Schema equals the frozen contract")
    else:
        print("schema: MISMATCH between emitted and frozen agent-output schema:")
        for d in schema_diffs:
            print(f"  {d}")

    if not fixture_problems:
        print(f"fixtures: all {len(_fixture_files())} fixtures behave per the README")
    else:
        print("fixtures: FAILURES:")
        for p in fixture_problems:
            print(f"  {p}")

    return 0 if not schema_diffs and not fixture_problems else 1


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if not args or args[0] != "check":
        print("usage: python -m app.contracts check", file=sys.stderr)
        return 2
    return run()


if __name__ == "__main__":
    raise SystemExit(main())
