"""Composition root: one HTTP process serving ingest + the read-only API."""

import argparse
import logging

from ax_server.analysis.engine import AnalysisConfig, AnalysisEngine
from ax_server.analysis.manifest import load_manifest
from ax_server.analysis.phases import PhaseCalendar
from ax_server.analysis.proposals import SqliteProposalLedger
from ax_server.analysis.runtime_edges import manifest_method_index
from ax_server.analysis.suppression import Suppressions, compose_suppressions
from ax_server.api.http import make_api_router
from ax_server.api.service import QueryService
from ax_server.collector.classification import EnvironmentPolicy, parse_environments
from ax_server.collector.http import Router, make_collector_router, serve
from ax_server.collector.service import CollectorService
from ax_server.store.sqlite_store import SqliteStore


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="ax_server")
    parser.add_argument("--db", default="auxin.sqlite")
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--suppress", default=".auxin/suppress.txt")
    parser.add_argument("--phases", default=".auxin/phases.json")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    # -- BUG #22b: the C50 gate, finally configurable ------------------
    parser.add_argument(
        "--allow-environments",
        default="",
        metavar="a,b,c",
        help=(
            "extra environment labels to treat as production, added to the built-in "
            "allowlist (production,prod). Use this when your JVMs report 'prod-eu' or "
            "'live' rather than mislabelling them."
        ),
    )
    parser.add_argument(
        "--reject-unclassified",
        action="store_true",
        help=(
            "refuse (403) any window that is not production-classified, instead of the "
            "default: store it, mark it livenessEvidence=false, count it, and use it as "
            "evidence for nothing. The default lets a first-time user see data flowing; "
            "this restores the strict pre-#22b behaviour."
        ),
    )
    # -- BUG #28: the default suppression list -------------------------
    parser.add_argument(
        "--no-default-suppressions",
        action="store_true",
        help=(
            "do not load the shipped compiler/Lombok suppression list (enum values()/"
            "valueOf, Lombok equals/hashCode/toString/canEqual/builder, @Generated "
            "members, record accessors); use only .auxin/suppress.txt."
        ),
    )
    parser.add_argument(
        "--list-suppressions",
        action="store_true",
        help="print every active suppression rule with the list it came from, then exit.",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s %(message)s")

    manifest = load_manifest(args.manifest)
    config = AnalysisConfig(
        phase_calendar=PhaseCalendar.from_file(args.phases),
        default_suppressions=not args.no_default_suppressions,
    )

    if args.list_suppressions:
        # Bug #28: the defaults must be VISIBLE. Composed exactly as the engine
        # composes them -- same function, same order, same manifest -- so what
        # is printed is what will match, not a second rendering of it.
        rules = compose_suppressions(
            Suppressions.from_file(args.suppress),
            manifest=manifest,
            include_defaults=config.default_suppressions,
        )
        print(rules.render())
        return 0

    store = SqliteStore(args.db)
    # The collector validates every `edges[]` endpoint against the manifest
    # that was actually shipped, so a dangling (class, idx) is refused at
    # ingest instead of becoming an unresolvable row.
    collector = CollectorService(
        store,
        known_methods=manifest_method_index(manifest),
        policy=EnvironmentPolicy(
            allow_environments=parse_environments(args.allow_environments),
            reject_non_production=args.reject_unclassified,
        ),
    )
    engine = AnalysisEngine(
        store,
        manifest,
        config=config,
        suppressions=Suppressions.from_file(args.suppress),
        ledger=SqliteProposalLedger(args.db + ".proposals"),
    )
    api = QueryService(engine, collector=collector)

    router = Router().extend(make_collector_router(collector)).extend(make_api_router(api))
    httpd = serve(router, host=args.host, port=args.port)
    log = logging.getLogger("gt")
    log.info("listening on http://%s:%d", args.host, args.port)
    log.info(
        "production allowlist: %s; unclassified windows are %s",
        sorted(collector.policy.allowed),
        "REFUSED 403 (--reject-unclassified)"
        if collector.policy.reject_non_production
        else "stored and marked livenessEvidence=false (evidence for nothing)",
    )
    log.info(
        "suppressions: %s (--list-suppressions to see them, "
        "--no-default-suppressions to drop the defaults)",
        engine.suppressions.counts_by_origin() or "none",
    )
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
