"""Read-only query surface over `analysis`.

READ ONLY. Nothing in this layer mutates a verdict, a proposal or a suppression
file, and there is no delete of any kind anywhere in this module. The one
mutating operation the system needs -- recording C55 human feedback -- is
deliberately NOT exposed here; see `record_feedback` at the bottom and the
note attached to it.
"""

from collections.abc import Sequence
from datetime import UTC, datetime, timedelta
from typing import Any

from ax_server.analysis.efp import efp_keys
from ax_server.analysis.engine import AnalysisEngine, AnalysisRun
from ax_server.analysis.models import DEAD_CANDIDATE, Verdict
from ax_server.collector.buckets import percentiles
from ax_server.collector.service import CollectorService

__all__ = ["QueryService"]


class QueryService:
    """One object the HTTP and MCP adapters both sit on top of."""

    def __init__(
        self,
        engine: AnalysisEngine,
        *,
        collector: CollectorService | None = None,
    ) -> None:
        self.engine = engine
        self.collector = collector

    # -- helpers ---------------------------------------------------------

    def _run(self, build_sha: str | None) -> AnalysisRun:
        # C53: verdicts are re-derived on every request, never served from a
        # snapshot. A new observation must be able to revoke a proposal
        # between two reads.
        return self.engine.derive(build_sha)

    @staticmethod
    def _json(verdicts: Sequence[Verdict]) -> list[dict[str, Any]]:
        return [v.to_json() for v in verdicts]

    # -- verdict queries -------------------------------------------------

    def verdicts_by_class(self, build_sha: str, cls: str) -> list[dict[str, Any]]:
        run = self._run(build_sha)
        return self._json([v for v in run.verdicts if v.cls == cls])

    def verdicts_by_package(self, build_sha: str, package: str) -> list[dict[str, Any]]:
        prefix = package.rstrip(".") + "."
        run = self._run(build_sha)
        return self._json(
            [v for v in run.verdicts if v.cls == package or v.cls.startswith(prefix)]
        )

    def verdicts_by_file(self, build_sha: str, source_file: str) -> list[dict[str, Any]]:
        wanted = {
            c.name
            for c in self.engine.manifest.classes
            if c.source_file == source_file or c.source_file.endswith("/" + source_file)
        }
        run = self._run(build_sha)
        return self._json([v for v in run.verdicts if v.cls in wanted])

    def dead_candidates(self, build_sha: str, limit: int = 100) -> dict[str, Any]:
        run = self._run(build_sha)
        items = [v for v in run.verdicts if v.status == DEAD_CANDIDATE][:limit]
        return {
            "buildSha": run.build_sha,
            "artifact": run.artifact,
            "windowDays": run.evidence.window_days,
            "phasesCovered": list(run.evidence.phase_coverage.covered),
            "phasesMissing": list(run.evidence.phase_coverage.missing),
            "count": len(items),
            "candidates": self._json(items),
            "posture": (
                "false-negative-biased. These are PROPOSALS for human review, "
                "re-derived and revocable on every run (C53). Nothing is ever "
                "auto-deleted. Closest published analogue measured 72% precision "
                "with roughly 1 in 3 flagged items genuinely removable (C57); "
                "~15% of static+dynamic-approved removals still break on unseen "
                "executions (JShrink)."
            ),
        }

    def summary(self, build_sha: str) -> dict[str, Any]:
        return self._run(build_sha).summary()

    # -- runtime queries -------------------------------------------------

    def hot_methods(
        self, build_sha: str, *, since_days: int = 7, limit: int = 50
    ) -> list[dict[str, Any]]:
        since = datetime.now(tz=UTC) - timedelta(days=since_days)
        rows_fn = getattr(self.engine.store, "tier2_rows", None)
        if rows_fn is None:
            return []
        out: list[dict[str, Any]] = []
        for row in rows_fn(build_sha, since)[:limit]:
            cls = str(row["class"])
            idx = int(row["idx"])
            buckets = self.engine.store.tier2_buckets(build_sha, cls, idx, since)
            method = self._method_name(cls, idx)
            out.append(
                {
                    "class": cls,
                    "idx": idx,
                    "method": method,
                    "calls": row["calls"],
                    "errors": row["errors"],
                    # Computed here, never sent by the agent (CONTRACTS 2 / C31).
                    "percentiles": percentiles(buckets),
                    "bucketScheme": "loglinear-16-v1",
                }
            )
        return out

    def _method_name(self, cls: str, idx: int) -> str | None:
        entry = self.engine.manifest.klass(cls)
        if entry is None:
            return None
        for m in entry.methods:
            if m.idx == idx:
                return f"{m.name}{m.desc}"
        return None

    def agent_health(self, build_sha: str) -> dict[str, Any]:
        windows = self.engine.store.observed_windows(build_sha)
        skipped: dict[str, int] = {}
        transform_failures = 0
        ring_dropped = 0
        degraded = 0
        clock_degraded = 0
        for w in windows:
            transform_failures += w.agent_health.transform_failures
            ring_dropped += w.agent_health.ring_dropped
            degraded += int(w.degraded)
            clock_degraded += int(w.agent_health.clock_degraded)
            for reason, count in w.agent_health.classes_skipped.items():
                skipped[reason] = skipped.get(reason, 0) + count
        result: dict[str, Any] = {
            "buildSha": build_sha,
            "windows": len(windows),
            "degradedWindows": degraded,
            "clockDegradedWindows": clock_degraded,
            "transformFailuresTotal": transform_failures,
            "ringDroppedTotal": ring_dropped,
            "classesSkippedTotal": skipped,
            "instances": sorted({w.instance_id for w in windows}),
            "note": (
                "degraded windows are usable as evidence of LIFE but never as "
                "evidence of death (CONTRACTS 2)"
            ),
        }
        if self.collector is not None:
            result["ingest"] = self.collector.health.snapshot()
        rejects = getattr(self.engine.store, "rejects", None)
        if rejects is not None:
            result["recentRejects"] = rejects(build_sha, 20)
        return result

    def coverage_windows(self, build_sha: str) -> dict[str, Any]:
        ev = self.engine.evidence(build_sha)
        def row(w: Any, usable: bool) -> dict[str, Any]:
            return {
                "windowId": w.window_id,
                "instanceId": w.instance_id,
                "start": w.start.isoformat(),
                "end": w.end.isoformat(),
                "durationSeconds": w.duration_seconds,
                "degraded": w.degraded,
                "environment": w.environment,
                "production": w.production,
                "testTainted": w.test_tainted,
                "usableAsDeathEvidence": usable,
            }
        return {
            "buildSha": build_sha,
            "windowDays": ev.window_days,
            "phasesCovered": list(ev.phase_coverage.covered),
            "phasesMissing": list(ev.phase_coverage.missing),
            "phaseDetail": dict(ev.phase_coverage.detail),
            "span": (
                [ev.phase_coverage.span[0].isoformat(), ev.phase_coverage.span[1].isoformat()]
                if ev.phase_coverage.span
                else None
            ),
            "usable": [row(w, True) for w in ev.usable_windows],
            "excluded": [row(w, False) for w in ev.excluded_windows],
        }

    def proposals(self, build_sha: str) -> list[dict[str, Any]]:
        return [
            {
                "class": p.ref.cls,
                "method": p.ref.name,
                "desc": p.ref.desc,
                "firstProposedAt": p.first_proposed_at.isoformat(),
                "lastConfirmedAt": p.last_confirmed_at.isoformat(),
                "active": p.active,
                "revokedReason": p.revoked_reason,
            }
            for p in self.engine.ledger.active(build_sha)
        ]

    def effective_false_positives(self) -> dict[str, Any]:
        stats = self.engine.efp.all_stats()
        return {
            "definition": (
                "Google Tricorder: any report where a user chooses not to take "
                "action to resolve it."
            ),
            "thresholds": {"probation": 0.10, "autoDisable": 0.25},
            "ruleClasses": [s.to_json() for s in stats],
        }

    def builds(self) -> list[str]:
        fn = getattr(self.engine.store, "builds", None)
        return fn() if fn else []

    # -- the one write, kept off the HTTP surface ------------------------

    def record_feedback(
        self, *, entry_point_kind: str, eligibility: str, actioned: bool
    ) -> None:
        """C55 feedback intake.

        Not routed over the read-only HTTP API on purpose: the IDE/MCP surfaces
        that will feed it do not exist yet, and an unauthenticated endpoint that
        can silently disable a safety rule class is a worse idea than a missing
        feature. Callable in-process so the counters and the self-disable path
        are exercised and tested today.
        """
        self.engine.efp.record_feedback(
            efp_keys(entry_point_kind, eligibility), actioned=actioned
        )
