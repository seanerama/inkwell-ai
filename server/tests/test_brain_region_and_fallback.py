"""Stage 30 regressions: agent-written ``source_region`` arrays list cleanly, and a
multi-term brain search with no AND match falls back to OR matching.

(1) ``GET /brain/{slug}`` 500'd for any entry persisted from a job's ``brain_writes``,
because ``BrainEntryOut.source_region`` was typed as an object while the stored value is
the contract ``Rect`` array. (2) ``websearch_to_tsquery`` ANDs every term, so the model's
natural four-word lookup found nothing a one-word query found. Real Postgres (conftest).
"""

from __future__ import annotations

from datetime import UTC, datetime

import pytest
from sqlalchemy import select

from app.agent import client as agent_client
from app.agent.tools import run_brain_search
from app.api.schemas import BrainEntryOut
from app.brain.store import compute_hash, create_entry, search_brain, search_entries
from app.config import get_settings
from app.db.base import get_sessionmaker
from app.db.models import BrainEntry
from app.jobs.queue import process_one
from tests.fakes import PNG_1X1_B64, FakeAnthropic, json_payload

_FACT = "Q3 offsite: Austin, 14 Oct"
_FOUR_WORDS = "Q3 offsite date location"
_REGION = [0.1, 0.2, 0.3, 0.4]


@pytest.fixture(autouse=True)
def _reset_client():
    yield
    agent_client.set_client(None)


@pytest.fixture
def agent_on(monkeypatch):
    monkeypatch.setattr(get_settings(), "agent_enabled", True)


@pytest.fixture
def brain_on(monkeypatch):
    monkeypatch.setattr(get_settings(), "brain_enabled", True)


def _run_worker() -> None:
    sm = get_sessionmaker()
    with sm() as session:
        assert process_one(session, "test-worker") is True


def _agent_write(client, auth) -> None:
    """Persist one entry the real way: a done job's validated ``brain_writes``."""
    response = {
        "summary": "Recorded the offsite.",
        "annotations": [],
        "cards": [
            {"kind": "answer", "title": "Noted", "body": "Saved.", "anchors": [], "actions": []}
        ],
        "brain_writes": [
            {"kind": "fact", "text": _FACT, "tags": ["offsite"], "source_region": _REGION}
        ],
    }
    agent_client.set_client(FakeAnthropic(json_payload(response)))
    resp = client.post("/v1/jobs", json={"type": "canvas.ask", "image": PNG_1X1_B64}, headers=auth)
    assert resp.status_code == 202, resp.text
    _run_worker()


def _seed(db, text: str, *, source_region=None) -> BrainEntry:
    entry, _ = create_entry(db, space_slug="work", kind="fact", text=text)
    if source_region is not None:
        entry.source_region = source_region
    db.commit()
    db.refresh(entry)
    return entry


# ---- (1) source_region is the Rect array --------------------------------------------


def test_listing_agent_written_entry_returns_200_and_the_array(
    client, auth, agent_on, brain_on, db
):
    _agent_write(client, auth)
    stored = db.execute(select(BrainEntry)).scalar_one()
    assert stored.source_region == _REGION  # stored JSONB is already the array
    assert stored.job_id is not None

    resp = client.get("/v1/brain/work", headers=auth)
    assert resp.status_code == 200, resp.text
    rows = resp.json()
    assert len(rows) == 1
    assert rows[0]["text"] == _FACT
    assert rows[0]["source_region"] == _REGION

    # The search path serialises the same row too.
    resp = client.get("/v1/brain/work", params={"q": "offsite"}, headers=auth)
    assert resp.status_code == 200, resp.text
    assert resp.json()[0]["source_region"] == _REGION


def test_legacy_object_region_serialises_as_the_array():
    out = BrainEntryOut.model_validate(
        {
            "id": "00000000-0000-0000-0000-000000000001",
            "space_slug": "work",
            "kind": "fact",
            "text": "x",
            "tags": [],
            "source_canvas_id": None,
            "source_region": {"x": 0.1, "y": 0.2, "w": 0.3, "h": 0.4},
            "job_id": None,
            "created_at": "2026-09-24T00:00:00Z",
        }
    )
    assert out.source_region == _REGION
    assert out.model_dump(mode="json")["source_region"] == _REGION


def test_legacy_object_row_lists_as_the_array(client, auth, brain_on, db):
    _seed(db, "legacy region row", source_region={"x": 0.5, "y": 0.5, "w": 0.25, "h": 0.25})
    resp = client.get("/v1/brain/work", headers=auth)
    assert resp.status_code == 200, resp.text
    assert resp.json()[0]["source_region"] == [0.5, 0.5, 0.25, 0.25]


def test_null_region_stays_null(client, auth, brain_on, db):
    _seed(db, "no region")
    resp = client.get("/v1/brain/work", headers=auth)
    assert resp.status_code == 200
    assert resp.json()[0]["source_region"] is None


@pytest.mark.parametrize(
    "bad",
    [[0.1, 0.2, 0.3], [0.1, 0.2, 0.3, 0.4, 0.5], [0.1, 0.2, 1.5, 0.4], {"x": 0.1, "y": 0.2}],
)
def test_region_must_be_four_normalised_numbers(bad):
    with pytest.raises(ValueError):
        BrainEntryOut.model_validate(
            {
                "id": "00000000-0000-0000-0000-000000000001",
                "space_slug": "work",
                "kind": "fact",
                "text": "x",
                "tags": [],
                "source_canvas_id": None,
                "source_region": bad,
                "job_id": None,
                "created_at": "2026-09-24T00:00:00Z",
            }
        )


# ---- (2) AND first, OR fallback -----------------------------------------------------


def test_four_word_query_falls_back_to_or(client, auth, brain_on, db):
    _seed(db, _FACT)
    _seed(db, "Buy milk on the way home")

    found = search_brain(db, "work", _FOUR_WORDS, limit=10)
    assert found.mode == "or"
    assert [e.text for e in found.entries] == [_FACT]

    resp = client.get("/v1/brain/work", params={"q": _FOUR_WORDS}, headers=auth)
    assert resp.status_code == 200, resp.text
    assert [r["text"] for r in resp.json()] == [_FACT]


def test_or_fallback_ranks_more_matching_terms_first(db):
    _seed(db, "Offsite snacks list")  # 1 of 4 terms
    _seed(db, _FACT)  # 2 of 4 terms (q3, offsite)
    found = search_brain(db, "work", _FOUR_WORDS, limit=10)
    assert found.mode == "or"
    assert [e.text for e in found.entries] == [_FACT, "Offsite snacks list"]


def test_multi_word_and_match_stays_and(db):
    _seed(db, _FACT)
    _seed(db, "Offsite snacks list")
    found = search_brain(db, "work", "offsite Austin", limit=10)
    assert found.mode == "and"
    assert [e.text for e in found.entries] == [_FACT]  # AND hit: no OR widening


def test_one_word_query_unchanged(db):
    _seed(db, _FACT)
    hit = search_brain(db, "work", "offsite", limit=10)
    assert hit.mode == "and" and [e.text for e in hit.entries] == [_FACT]
    miss = search_brain(db, "work", "elephant", limit=10)
    assert miss.mode == "and" and miss.entries == []  # one lexeme: no OR retry


def test_multi_word_with_no_term_matching_is_empty_or(client, auth, brain_on, db):
    _seed(db, _FACT)
    found = search_brain(db, "work", "elephant giraffe", limit=10)
    assert found.mode == "or" and found.entries == []
    resp = client.get("/v1/brain/work", params={"q": "elephant giraffe"}, headers=auth)
    assert resp.status_code == 200 and resp.json() == []


def test_or_fallback_respects_space_limit_and_soft_delete(db):
    _seed(db, _FACT)
    gone = _seed(db, "Q3 planning notes")
    gone.deleted_at = datetime.now(UTC)
    create_entry(db, space_slug="personal", kind="fact", text="Q3 offsite elsewhere")
    db.commit()

    found = search_brain(db, "work", _FOUR_WORDS, limit=10)
    assert [e.text for e in found.entries] == [_FACT]
    _seed(db, "Offsite snacks list")
    assert len(search_entries(db, "work", _FOUR_WORDS, limit=1)) == 1


@pytest.mark.parametrize(
    "q", ["o'brien & | ! :* <-> ()", "'); DROP TABLE brain_entries; --", "a | b", "!!! ???"]
)
def test_hostile_queries_are_bound_parameters(client, auth, brain_on, db, q):
    _seed(db, _FACT)
    resp = client.get("/v1/brain/work", params={"q": q}, headers=auth)
    assert resp.status_code == 200, resp.text
    assert db.execute(select(BrainEntry.hash)).scalar_one() == compute_hash(_FACT)


# ---- the brain_search tool shares the fallback ---------------------------------------


def test_brain_search_tool_gets_the_same_fallback(db):
    _seed(db, _FACT)
    result = run_brain_search(db, "work", {"query": _FOUR_WORDS})
    assert result.is_error is False
    assert result.lookup == {"query": _FOUR_WORDS, "count": 1, "mode": "or"}
    assert f"[fact] {_FACT}" in result.content


def test_brain_search_tool_one_word_is_and(db):
    _seed(db, _FACT)
    result = run_brain_search(db, "work", {"query": "offsite"})
    assert result.lookup == {"query": "offsite", "count": 1, "mode": "and"}
