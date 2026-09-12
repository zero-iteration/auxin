"""`python -m ax_server.mcp --db ... --manifest ...` -- MCP over stdio."""

import argparse
import logging
import sys

from ax_server.analysis.engine import AnalysisConfig, AnalysisEngine
from ax_server.analysis.manifest import load_manifest
from ax_server.analysis.phases import PhaseCalendar
from ax_server.analysis.proposals import SqliteProposalLedger
from ax_server.analysis.suppression import Suppressions
from ax_server.api.service import QueryService
from ax_server.mcp.server import McpServer
from ax_server.store.sqlite_store import SqliteStore


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="ax_server.mcp")
    parser.add_argument("--db", required=True, help="SQLite file written by the collector")
    parser.add_argument("--manifest", required=True, help="auxin-manifest.json")
    parser.add_argument("--suppress", default=".auxin/suppress.txt")
    parser.add_argument("--phases", default=".auxin/phases.json")
    parser.add_argument("--proposals-db", default=None)
    # Bug #28: the same switch the collector CLI has, for the same reason --
    # an MCP client asking `gt_dead_candidates` must be able to see, and turn
    # off, a filter it did not configure. `gt_suppressions` lists the set.
    parser.add_argument(
        "--no-default-suppressions",
        action="store_true",
        help="use only .auxin/suppress.txt; drop the shipped compiler/Lombok list.",
    )
    args = parser.parse_args(argv)

    # stderr only: stdout is the JSON-RPC channel.
    logging.basicConfig(stream=sys.stderr, level=logging.WARNING)

    store = SqliteStore(args.db)
    manifest = load_manifest(args.manifest)
    engine = AnalysisEngine(
        store,
        manifest,
        config=AnalysisConfig(
            phase_calendar=PhaseCalendar.from_file(args.phases),
            default_suppressions=not args.no_default_suppressions,
        ),
        suppressions=Suppressions.from_file(args.suppress),
        ledger=SqliteProposalLedger(args.proposals_db or (args.db + ".proposals")),
    )
    McpServer(QueryService(engine)).run()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
