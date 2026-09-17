"""Operator CLI (``inkwell``): device-token management + seeding (ADR-0008).

inkwell token create --name <name>   # mint a token, print the plaintext once
inkwell token revoke <id>            # revoke a token by id
inkwell token list                   # list tokens (never prints the secret)
inkwell token migrate-check          # exit 0 if all live tokens are on the newest pepper
inkwell db seed                      # idempotently seed the four default spaces
inkwell canary [--space work] [--timeout 90]  # run one live agent job (deploy gate)
"""

from __future__ import annotations

import argparse
import sys

from app.db.base import get_sessionmaker
from app.db.models import DeviceToken
from app.db.seed import seed_default_spaces
from app.security.tokens import create_token, revoke_token


def _cmd_token_create(args: argparse.Namespace) -> int:
    sm = get_sessionmaker()
    with sm() as session:
        row, plaintext = create_token(session, args.name)
    print(f"id: {row.id}")
    print(f"name: {row.name}")
    print(f"token: {plaintext}")
    print("Store this token now; it is not recoverable.")
    return 0


def _cmd_token_revoke(args: argparse.Namespace) -> int:
    sm = get_sessionmaker()
    with sm() as session:
        ok = revoke_token(session, args.id)
    if not ok:
        print(f"no active token with id {args.id}", file=sys.stderr)
        return 1
    print(f"revoked {args.id}")
    return 0


def _cmd_token_list(_: argparse.Namespace) -> int:
    from sqlalchemy import select

    sm = get_sessionmaker()
    with sm() as session:
        rows = session.execute(select(DeviceToken).order_by(DeviceToken.created_at)).scalars().all()
    for r in rows:
        state = "revoked" if r.revoked_at else "active"
        last_seen = r.last_seen_at.isoformat() if r.last_seen_at else "never"
        print(f"{r.id}  {r.name:20s}  {state:8s}  v{r.hash_version}  last_seen={last_seen}")
    return 0


def _cmd_token_migrate_check(_: argparse.Namespace) -> int:
    """Exit 0 when no live token still lags the newest pepper generation, else 1.

    ``target`` is MAX(hash_version) over ALL rows. A live (revoked_at IS NULL) token with
    hash_version < target is a straggler that has not been seen since the pepper rotated;
    while any exists the operator must NOT unset INKWELL_TOKEN_PEPPER_PREVIOUS. Zero
    tokens, or all live tokens already at target, exit 0. The runbook runs this AFTER
    using the tablet once, so at least one token has migrated and target has advanced.
    """
    from sqlalchemy import select

    sm = get_sessionmaker()
    with sm() as session:
        rows = session.execute(select(DeviceToken)).scalars().all()
    if not rows:
        return 0
    target = max(r.hash_version for r in rows)
    laggers = [r for r in rows if r.revoked_at is None and r.hash_version < target]
    if not laggers:
        return 0
    print(
        f"{len(laggers)} live token(s) still on an older pepper generation (target v{target}):",
        file=sys.stderr,
    )
    for r in laggers:
        print(f"  {r.id}  {r.name}  v{r.hash_version}", file=sys.stderr)
    return 1


def _cmd_db_seed(_: argparse.Namespace) -> int:
    sm = get_sessionmaker()
    with sm() as session:
        created = seed_default_spaces(session)
    print(f"seeded {created} space(s)")
    return 0


def _cmd_canary(args: argparse.Namespace) -> int:
    from app.canary.run import run_canary

    return run_canary(space=args.space, timeout=args.timeout)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="inkwell")
    sub = parser.add_subparsers(dest="group", required=True)

    token = sub.add_parser("token", help="device token management")
    token_sub = token.add_subparsers(dest="action", required=True)
    create = token_sub.add_parser("create", help="mint a token")
    create.add_argument("--name", required=True)
    create.set_defaults(func=_cmd_token_create)
    revoke = token_sub.add_parser("revoke", help="revoke a token by id")
    revoke.add_argument("id")
    revoke.set_defaults(func=_cmd_token_revoke)
    listp = token_sub.add_parser("list", help="list tokens")
    listp.set_defaults(func=_cmd_token_list)
    migrate_check = token_sub.add_parser(
        "migrate-check",
        help="exit 0 if no live token lags the newest pepper generation, else 1",
    )
    migrate_check.set_defaults(func=_cmd_token_migrate_check)

    db = sub.add_parser("db", help="database management")
    db_sub = db.add_subparsers(dest="action", required=True)
    seed = db_sub.add_parser("seed", help="seed default spaces (idempotent)")
    seed.set_defaults(func=_cmd_db_seed)

    canary = sub.add_parser("canary", help="run one live agent job (deploy gate)")
    canary.add_argument("--space", default="work", help="space slug (default: work)")
    canary.add_argument(
        "--timeout", type=float, default=90, help="seconds to wait for the worker (default: 90)"
    )
    canary.set_defaults(func=_cmd_canary)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
