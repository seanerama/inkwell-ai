"""Operator CLI (``inkwell``): device-token management + seeding (ADR-0008).

inkwell token create --name <name>   # mint a token, print the plaintext once
inkwell token revoke <id>            # revoke a token by id
inkwell token list                   # list tokens (never prints the secret)
inkwell token rotate-pepper --begin  # start a pepper rotation (bump the generation)
inkwell token rotate-pepper --end    # close the window (refuses while migrate-check is red)
inkwell token migrate-check          # exit 0 only when no live token lags the generation
inkwell db seed                      # idempotently seed the four default spaces
inkwell canary [--space work] [--timeout 90]  # run one live agent job (deploy gate)
"""

from __future__ import annotations

import argparse
import sys

from app.config import get_settings
from app.db.base import get_sessionmaker
from app.db.models import DeviceToken
from app.db.seed import seed_default_spaces
from app.security.pepper import get_pepper_generation, set_pepper_generation
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


def _pepper_stragglers(session) -> tuple[int, list[DeviceToken]]:
    """The current pepper generation and the live tokens whose hash_version lags it.

    A live (``revoked_at IS NULL``) token with ``hash_version < generation`` has not been
    re-hashed since the rotation began. Uses the EXPLICIT generation from ``app_meta``
    (Stage 20), not MAX(hash_version): MAX false-greens right after a rotation starts,
    before any token has migrated, which was the Stage 20 bug.
    """
    from sqlalchemy import select

    generation = get_pepper_generation(session)
    live = (
        session.execute(select(DeviceToken).where(DeviceToken.revoked_at.is_(None))).scalars().all()
    )
    return generation, [r for r in live if r.hash_version < generation]


def _grace_window_red() -> bool:
    """True when the grace window must stay open: previous pepper set AND a live straggler.

    Fails closed — immediately after ``rotate-pepper --begin`` (generation bumped, the
    previous pepper set, every token still on the old generation) this is True, and it
    only goes False once every live token has been re-hashed to the current generation.
    """
    if not get_settings().token_pepper_previous:
        return False
    sm = get_sessionmaker()
    with sm() as session:
        _, laggers = _pepper_stragglers(session)
    return bool(laggers)


def _cmd_token_migrate_check(_: argparse.Namespace) -> int:
    """Exit 1 while the grace window must stay open, else 0 (Stage 20, fails closed).

    Red (exit 1) when ``INKWELL_TOKEN_PEPPER_PREVIOUS`` is set AND any live token's
    ``hash_version`` is below the current pepper generation recorded in ``app_meta``.
    Green (exit 0) otherwise — no rotation in progress, or every live token has migrated.
    """
    prev_set = bool(get_settings().token_pepper_previous)
    sm = get_sessionmaker()
    with sm() as session:
        generation, laggers = _pepper_stragglers(session)
    if prev_set and laggers:
        print(
            f"{len(laggers)} live token(s) still below pepper generation {generation}; "
            "keep INKWELL_TOKEN_PEPPER_PREVIOUS set:",
            file=sys.stderr,
        )
        for r in laggers:
            print(f"  {r.id}  {r.name}  v{r.hash_version}", file=sys.stderr)
        return 1
    return 0


def _cmd_token_rotate_pepper(args: argparse.Namespace) -> int:
    """Begin or end a token-pepper rotation grace window (Stage 20). Never prints secrets."""
    if args.begin:
        sm = get_sessionmaker()
        with sm() as session:
            current = get_pepper_generation(session)
            new_generation = current + 1
            set_pepper_generation(session, new_generation)
        print(f"pepper generation bumped to {new_generation} (was {current}).")
        print("Next steps (edit .env on the host, mode 600 — this command touches no secret):")
        print("  1. Back up .env:  cp .env .env.bak-$(date +%Y%m%d-%H%M%S) && chmod 600 .env.bak-*")
        print("  2. In .env set INKWELL_TOKEN_PEPPER_PREVIOUS=<old>, INKWELL_TOKEN_PEPPER=<new>.")
        print("  3. dc up -d api worker")
        print("  4. Use the tablet once (let it sync) or run the operator curl to migrate tokens.")
        print("  5. inkwell token migrate-check  (RED until every live token reaches the new gen).")
        print("  6. When green:  inkwell token rotate-pepper --end")
        return 0

    # --end
    if _grace_window_red():
        print(
            "refusing to end the grace window: migrate-check is RED (a live token still "
            "lags the current generation). Run `inkwell token migrate-check` for details.",
            file=sys.stderr,
        )
        return 1
    print("migrate-check is green; safe to close the grace window:")
    print("  1. Clear INKWELL_TOKEN_PEPPER_PREVIOUS in .env (leave it empty).")
    print("  2. dc up -d api worker")
    return 0


def _cmd_db_seed(_: argparse.Namespace) -> int:
    sm = get_sessionmaker()
    with sm() as session:
        created = seed_default_spaces(session)
    print(f"seeded {created} space(s)")
    return 0


def _cmd_canary(args: argparse.Namespace) -> int:
    from app.canary.run import run_canary

    return run_canary(space=args.space, timeout=args.timeout)


def _load_space(session, slug: str):
    from sqlalchemy import select

    from app.db.models import Space

    return session.execute(select(Space).where(Space.slug == slug)).scalar_one_or_none()


def _read_file_bytes(path: str) -> bytes:
    """Read the push payload from a path, or from stdin when ``path`` is ``-``."""
    if path == "-":
        return sys.stdin.buffer.read()
    with open(path, "rb") as fh:
        return fh.read()


def _cmd_push_document(args: argparse.Namespace) -> int:
    if not get_settings().push_enabled:
        print("push disabled", file=sys.stderr)
        return 2
    from app.push.service import PushError, push_document

    data = _read_file_bytes(args.file)
    title = args.title or "Document"
    sm = get_sessionmaker()
    with sm() as session:
        space = _load_space(session, args.space)
        if space is None:
            print(f"no space with slug {args.space}", file=sys.stderr)
            return 1
        try:
            job, canvases = push_document(
                session, space, data, mime=None, title=title, card_body=args.note
            )
        except PushError as exc:
            print(f"push rejected: {exc}", file=sys.stderr)
            return 1
    print(f"job: {job.id}")
    for canvas in canvases:
        print(f"canvas: {canvas.id}")
    return 0


def _cmd_push_canvas(args: argparse.Namespace) -> int:
    if not get_settings().push_enabled:
        print("push disabled", file=sys.stderr)
        return 2
    from app.push.service import A4_H, A4_W, push_canvas

    width, height = (A4_H, A4_W) if args.landscape else (A4_W, A4_H)
    sm = get_sessionmaker()
    with sm() as session:
        space = _load_space(session, args.space)
        if space is None:
            print(f"no space with slug {args.space}", file=sys.stderr)
            return 1
        job, canvas = push_canvas(session, space, args.title, width, height, card_body=args.note)
    print(f"job: {job.id}")
    print(f"canvas: {canvas.id}")
    return 0


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
    rotate = token_sub.add_parser(
        "rotate-pepper",
        help="begin/end a token-pepper rotation grace window",
    )
    rotate_mode = rotate.add_mutually_exclusive_group(required=True)
    rotate_mode.add_argument(
        "--begin", action="store_true", help="bump the pepper generation and print next steps"
    )
    rotate_mode.add_argument(
        "--end", action="store_true", help="close the window (refuses while migrate-check is red)"
    )
    rotate.set_defaults(func=_cmd_token_rotate_pepper)
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

    push = sub.add_parser("push", help="push a document or blank canvas to a space (to_user)")
    push_sub = push.add_subparsers(dest="action", required=True)
    push_doc = push_sub.add_parser("document", help="push a PDF/PNG/JPEG as agent-origin canvases")
    push_doc.add_argument("--space", required=True, help="target space slug")
    push_doc.add_argument("--file", required=True, help="path to the file, or - for stdin")
    push_doc.add_argument("--title", default=None, help="canvas/document title")
    push_doc.add_argument("--note", default=None, help="optional answer-card body")
    push_doc.set_defaults(func=_cmd_push_document)
    push_canvas_p = push_sub.add_parser("canvas", help="push a blank agent-origin canvas")
    push_canvas_p.add_argument("--space", required=True, help="target space slug")
    push_canvas_p.add_argument("--title", required=True, help="canvas title")
    push_canvas_p.add_argument(
        "--landscape", action="store_true", help="landscape A4 (default portrait)"
    )
    push_canvas_p.add_argument("--note", default=None, help="optional answer-card body")
    push_canvas_p.set_defaults(func=_cmd_push_canvas)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
