"""The contract check must pass: schema equality + all eight fixtures per the README."""

from app.contracts.check import check_fixtures, check_schema_equality, run


def test_schema_equality_no_diffs():
    assert check_schema_equality() == []


def test_all_fixtures_behave():
    assert check_fixtures() == []


def test_contract_check_run_exits_zero():
    assert run() == 0
