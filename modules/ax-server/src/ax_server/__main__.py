"""Composition root: one HTTP process serving ingest + the read-only API."""

import argparse
import logging

from ax_server.analysis.engine import AnalysisConfig, AnalysisEngine
from ax_server.analysis.manifest import load_manifest
from ax_server.analysis.phases import PhaseCalendar
from ax_server.analysis.proposals import SqliteProposalLedger
from ax_server.analysis.runtime_edges import manifest_method_index
from ax_server.analysis.suppression import Suppressions
from ax_server.api.http import make_api_router
from ax_server.api.service import QueryService
from ax_server.collector.http import Router, make_collector_router, serve
from ax_server.collector.service import CollectorService
from ax_server.store.sqlite_store import SqliteStore


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="ax_server")
    parser.add_argument("--db", default="auxin.sqlite")
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--suppress", default=".auxin/suppress.txt")
    parser.add_argument("--phases", default=".auxin/phases.json")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s %(message)s")

    store = SqliteStore(args.db)
    manifest = load_manifest(args.manifest)
    # The collector validates every `edges[]` endpoint against the manifest
    # that was actually shipped, so a dangling (class, idx) is refused at
    # ingest instead of becoming an unresolvable row.
    collector = CollectorService(store, known_methods=manifest_method_index(manifest))
    engine = AnalysisEngine(
        store,
        manifest,
        config=AnalysisConfig(phase_calendar=PhaseCalendar.from_file(args.phases)),
        suppressions=Suppressions.from_file(args.suppress),
        ledger=SqliteProposalLedger(args.db + ".proposals"),
    )
    api = QueryService(engine, collector=collector)

    router = Router().extend(make_collector_router(collector)).extend(make_api_router(api))
    httpd = serve(router, host=args.host, port=args.port)
    logging.getLogger("gt").info("listening on http://%s:%d", args.host, args.port)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
