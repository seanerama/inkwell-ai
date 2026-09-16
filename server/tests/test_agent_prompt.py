"""Unit tests for prompt assembly (SPEC §10.3) and effort selection (ADR-0006)."""

from __future__ import annotations

from app.agent import client, prompt
from tests.fakes import repo_root


def _contract_coordinate_sentence() -> str:
    """Extract the verbatim blockquote sentence from the frozen coordinate contract."""
    text = (repo_root() / "contracts" / "coordinate-mapping.md").read_text()
    marker = "**Prompt (server):**"
    after = text.split(marker, 1)[1]
    lines = []
    for line in after.splitlines():
        stripped = line.strip()
        if stripped.startswith(">"):
            lines.append(stripped[1:].strip())
        elif lines:
            break
    return " ".join(lines)


def test_coordinate_sentence_is_verbatim():
    assert prompt.COORDINATE_SENTENCE == _contract_coordinate_sentence()


def test_prompt_assembly_order():
    p = prompt.build_system_prompt(
        "SPACE PROMPT HERE", brain_context="", job_type="canvas.annotate"
    )
    block = f"{prompt.BRAIN_CONTEXT_OPEN}\n{prompt.BRAIN_CONTEXT_CLOSE}"
    i_coord = p.index(prompt.COORDINATE_SENTENCE)
    i_space = p.index("SPACE PROMPT HERE")
    i_brain = p.index(block)
    i_guidance = p.index("Task: annotate the canvas")
    # Fixed order: preamble (coordinate sentence) -> space prompt -> brain -> job guidance.
    assert i_coord < i_space < i_brain < i_guidance


def test_preamble_describes_output_schema():
    p = prompt.build_system_prompt(job_type="canvas.annotate")
    assert "summary" in p and "annotations" in p and "brain_writes" in p
    assert "highlight" in p and "margin_note" in p  # vocabulary from SPEC §6


def test_brain_delimiter_present_and_empty():
    p = prompt.build_system_prompt(job_type="canvas.annotate")
    block = f"{prompt.BRAIN_CONTEXT_OPEN}\n{prompt.BRAIN_CONTEXT_CLOSE}"
    assert block in p
    # And the preamble marks that block as data, not instructions.
    assert "not instructions" in p


def test_brain_delimiter_marks_data_notice():
    p = prompt.build_system_prompt(job_type="canvas.annotate")
    assert prompt.BRAIN_CONTEXT_OPEN in p and prompt.BRAIN_CONTEXT_CLOSE in p


def test_annotate_guidance_has_diagram_vocabulary():
    p = prompt.build_system_prompt(job_type="canvas.annotate")
    # The exact substring an existing test (test_canvas_ask) also depends on.
    assert "Task: annotate the canvas." in p
    # Stage 9 diagram-quality markup guidance.
    assert "arrow" in p and "margin_note" in p
    assert "depends on" in p and "blocks" in p
    assert "4 words or fewer" in p
    assert "3-8 precise marks" in p
    # The schema description now spells out each type's fields + coordinate meaning.
    assert "rx/ry are radii" in p
    assert "right-hand margin gutter" in p


def test_effort_per_job_type():
    assert client.effort_for("canvas.annotate") == "medium"
    assert client.effort_for("canvas.ask") == "low"
    assert client.effort_for("canvas.extract") == "low"
    assert client.effort_for("canvas.formalize") == "high"
