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
from ax_server.analysis.manifest import MethodEntry
from ax_server.analysis.models import (
    DEAD_CANDIDATE,
    LIVE,
    EligibilityClass,
    MethodRef,
    Verdict,
)
from ax_server.analysis.runtime_edges import RuntimeCallGraph
from ax_server.analysis.suppression import Suppressions
from ax_server.collector.buckets import percentiles
from ax_server.collector.classification import NON_PRODUCTION_MEANING, UNCLASSIFIED
from ax_server.collector.health import ERROR_ATTRIBUTION_NOTE
from ax_server.collector.service import CollectorService
from ax_server.store.models import EdgeAggregate

__all__ = ["QueryService"]

#: Attached to every response that carries an edge count. The number is a
#: count of observations in SAMPLED traces; presenting it as a call total
#: would be a lie by a factor of the sample rate (1024 by default).
COUNT_SEMANTICS = (
    "`sampledObservations` counts observations in SAMPLED traces, NOT calls. Multiply by "
    "`edgesSampleRate` for an order-of-magnitude estimate (`estimatedCalls`), never for an "
    "exact figure, and never compare two counts recorded at different rates (CONTRACTS 2 v3)."
)

#: Attached to every response that reports an installed-probe gap. The whole
#: point of bug #18 is that this number is a CONFIGURATION fact, and the
#: failure mode it prevents is reading it as a code fact.
INSTALL_MASK_SEMANTICS = (
    "`observableButNeverInstrumented` counts methods the manifest declares dynamically "
    "observable that NO JVM ever installed a probe at (ax.tier1.enabled=false, "
    "frameEmissionUnsupported for a bytecode shape, or a class-file fallback). Their probe "
    "bits are permanently zero because nothing can write them, so they are SILENCE and can "
    "never be DEAD_CANDIDATEs. A large number here is a misconfigured deployment, not a "
    "codebase full of dead code (bug #18)."
)

#: Attached to every response built from the static manifest graph.
STATIC_GRAPH_SEMANTICS = (
    "The static graph OVER-approximates (a call site in the bytecode need never execute) and "
    "is also unsound in the other direction: A5 measured 61% of methods that actually execute "
    "missing from static call graphs. It is reported separately from the runtime graph and the "
    "two are never merged into one 'reachable' flag."
)


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
        never_instrumented = sum(
            1 for v in run.verdicts if v.eligibility is EligibilityClass.NO_PROBE_INSTALLED
        )
        return {
            "buildSha": run.build_sha,
            "artifact": run.artifact,
            "windowDays": run.evidence.window_days,
            "phasesCovered": list(run.evidence.phase_coverage.covered),
            "phasesMissing": list(run.evidence.phase_coverage.missing),
            "count": len(items),
            "candidates": self._json(items),
            # Bug #18, reported NEXT TO the count it explains: a short list
            # because nothing was instrumented must not read as a short list
            # because nothing is dead.
            "instrumentation": {
                "maskReported": run.evidence.instrumentation.reported,
                "observableButNeverInstrumented": never_instrumented,
                "note": INSTALL_MASK_SEMANTICS,
            },
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

    # -- instrumentation coverage (bug #18) ------------------------------

    def instrumentation_gaps(
        self, build_sha: str, *, limit: int = 50, methods_per_class: int = 20
    ) -> dict[str, Any]:
        """"Which methods COULD we have observed?" -- per build and per class.

        This exists so a misconfigured deployment is visible instead of
        looking like a codebase full of dead code. Before bug #18 was closed,
        a window from a JVM with `ax.tier1.enabled=false` shipped an all-zero
        bitset while every method stayed `dynamicallyObservable: true`, and
        that is indistinguishable from "every method in your codebase is
        dead". Now those indices are withheld from the death argument -- and
        withholding them silently would be its own failure, so they are
        counted here.

        The three cases an operator has to be able to tell apart are reported
        as three separate counts, never summed:

            NOT_DYNAMICALLY_OBSERVABLE  the manifest says it cannot be covered (C51)
            NO_PROBE_INSTALLED          observable, but no JVM ever instrumented it (#18)
            DE_INSTRUMENTED             our own tier-1b stripped the probe (C4)
        """
        run = self._run(build_sha)
        probes = run.evidence.instrumentation
        by_class: dict[str, dict[str, Any]] = {}
        totals = {
            "OBSERVABLE": 0,
            "NO_PROBE_INSTALLED": 0,
            "NOT_DYNAMICALLY_OBSERVABLE": 0,
            "DE_INSTRUMENTED": 0,
        }
        for v in run.verdicts:
            key = str(v.eligibility)
            totals[key] = totals.get(key, 0) + 1
            row = by_class.setdefault(
                v.cls,
                {
                    "class": v.cls,
                    "methods": 0,
                    "observableButNeverInstrumented": 0,
                    "notDynamicallyObservable": 0,
                    "deInstrumented": 0,
                    "installMaskReported": v.probe_install_mask_reported,
                    "installedIndices": probes.installed_count(v.cls),
                    "uninstrumentedMethods": [],
                },
            )
            row["methods"] += 1
            if v.eligibility is EligibilityClass.NO_PROBE_INSTALLED:
                row["observableButNeverInstrumented"] += 1
                row["uninstrumentedMethods"].append(
                    {"method": f"{v.method}{v.desc}", "idx": v.probe_idx}
                )
            elif v.eligibility is EligibilityClass.NOT_DYNAMICALLY_OBSERVABLE:
                row["notDynamicallyObservable"] += 1
            elif v.eligibility is EligibilityClass.DE_INSTRUMENTED:
                row["deInstrumented"] += 1

        ranked = sorted(
            by_class.values(),
            key=lambda r: (-r["observableButNeverInstrumented"], r["class"]),
        )
        for row in ranked:
            listed = row["uninstrumentedMethods"][:methods_per_class]
            row["uninstrumentedMethodsTruncated"] = len(
                row["uninstrumentedMethods"]
            ) - len(listed)
            row["uninstrumentedMethods"] = listed

        windows = list(run.evidence.usable_windows) + list(run.evidence.excluded_windows)
        strip_missing = sum(w.agent_health.strip_mask_missing for w in windows)
        strip_windows = sum(1 for w in windows if w.agent_health.strip_mask_missing)
        never = totals["NO_PROBE_INSTALLED"]
        observable_total = never + totals["OBSERVABLE"]
        return {
            "buildSha": build_sha,
            "maskReported": probes.reported,
            "maskSupportedByStore": probes.supported,
            "classesWithMask": probes.classes_reported,
            "methods": len(run.verdicts),
            "observableButNeverInstrumented": never,
            "observableMethods": observable_total,
            "byEligibility": totals,
            "classes": len(by_class),
            # Worst offenders first, and BOUNDED: a real build has tens of
            # thousands of methods and this answer is read by an LLM. The
            # totals above are always complete -- only the listing is capped.
            "classesListed": min(limit, len(by_class)),
            "byClass": ranked[:limit],
            "stripMaskMissing": {
                "total": strip_missing,
                "windows": strip_windows,
                "note": (
                    "The agent could not determine which probes it had installed and "
                    "shipped an ALL-ZERO mask -- losing candidates, never inventing "
                    "them. A non-zero count here EXPLAINS missing candidates."
                ),
            },
            "installedIndices": probes.installed_indices,
            "classesWithNothingInstalled": probes.classes_with_nothing_installed,
            "diagnosis": self._install_diagnosis(
                probes_reported=probes.reported,
                supported=probes.supported,
                installed_indices=probes.installed_indices,
                never=never,
                observable_total=observable_total,
                strip_missing=strip_missing,
            ),
            "countSemantics": INSTALL_MASK_SEMANTICS,
            "readingRule": probes.caveat(),
        }

    @staticmethod
    def _install_diagnosis(
        *,
        probes_reported: bool,
        supported: bool,
        installed_indices: int,
        never: int,
        observable_total: int,
        strip_missing: int,
    ) -> str:
        if not supported:
            return (
                "This store cannot hold an installed-probe mask, so the bug #18 gate is "
                "not running: an uninstrumented index is still indistinguishable from an "
                "unexecuted one. Use a store implementing ProbeInstallStore."
            )
        if not probes_reported:
            return (
                "No window reported coverage[].probesInstalled -- a pre-#18 agent. "
                "Verdicts for this build were computed WITHOUT the gate, so a JVM with "
                "tier-1 disabled would still read as dead code. Upgrade the agent."
            )
        if not installed_indices:
            # Keyed on the MASK, not on the verdict totals: classes that never
            # reported at all would otherwise dilute the signature away.
            return (
                f"NOT ONE probe index was installed anywhere in this build ({never} "
                "observable method(s) affected). That is the signature of a deployment "
                "reporting with tier-1 disabled (ax.tier1.enabled=false): all-zero "
                "bitsets, every method still dynamicallyObservable. There is NO death "
                "evidence in this build, and a zero DEAD_CANDIDATE count here means "
                "'we could not look', not 'nothing is dead'."
            )
        if never:
            return (
                f"{never} of {observable_total} observable method(s) were never "
                "instrumented by any JVM -- typically per-method frame-emission skips "
                "for a bytecode shape, or a class-file fallback. Those methods are "
                "reported UNKNOWN with reason `no-probe-installed` and are excluded "
                "from the death argument."
            )
        if strip_missing:
            return (
                f"Every observable method is instrumented, but the agent shipped an "
                f"all-zero mask {strip_missing} time(s) (stripMaskMissing), which can "
                "only have LOST candidates, never invented one."
            )
        return (
            "Every method the manifest declares observable had a probe installed by at "
            "least one JVM, so an unset bit in this build is a real observation."
        )

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
            # BUG #24: the exception-class breakdown rides WITH the error count
            # instead of being a separate query nobody makes. "1201 calls, 3
            # errors" was the whole answer before; "3 errors, all
            # java.net.SocketTimeoutException" is the answer the README
            # promised.
            # `.get` rather than `[...]`: `tier2_rows` is an ADAPTER EXTRA, not
            # part of the frozen port, so a store that has not grown the key
            # must keep working -- it simply reports the count with no names.
            classes = dict(row.get("errorClasses") or {})
            entry: dict[str, Any] = {
                "class": cls,
                "idx": idx,
                "method": method,
                "calls": row["calls"],
                "errors": row["errors"],
                # Computed here, never sent by the agent (CONTRACTS 2 / C31).
                "percentiles": percentiles(buckets),
                "bucketScheme": "loglinear-16-v1",
            }
            if classes:
                entry["errorClasses"] = classes.get("byClass", {})
                entry["errorsAttributed"] = classes.get("attributed", 0)
                entry["errorsUnattributed"] = classes.get("unattributed", 0)
                entry["errorTypesAvailable"] = classes.get("typesAvailable", False)
                entry["errorTypesSource"] = classes.get("source")
                entry["errorReading"] = self._error_reading(dict(classes))
            out.append(entry)
        return out

    # -- runtime call graph (SCOPE-v3) -----------------------------------
    #
    # Every response in this block carries `edgesSampleRate` next to its
    # counts and the absence caveat next to its lists. That is not
    # decoration: a caller who sees `sampledObservations: 4` and concludes
    # "four calls" is wrong by ~1024x, and a caller who sees an empty list
    # and concludes "nothing calls this" has just deleted live code.

    def callers_of(
        self, build_sha: str, cls: str, method: str, *, limit: int = 50
    ) -> dict[str, Any]:
        """OBSERVED inbound edges for a method. Empty != no callers."""
        return self._edge_query(build_sha, cls, method, inbound=True, limit=limit)

    def callees_of(
        self, build_sha: str, cls: str, method: str, *, limit: int = 50
    ) -> dict[str, Any]:
        """OBSERVED outbound edges for a method. Empty != calls nothing."""
        return self._edge_query(build_sha, cls, method, inbound=False, limit=limit)

    def blast_radius(self, build_sha: str, cls: str, method: str) -> dict[str, Any]:
        """"If I delete this, what breaks?" -- answered from observed data.

        The question people actually ask before deleting a method is not "is
        it reachable" but "who calls it, and are they alive". Until SCOPE-v3
        that could only be answered from a static graph measured 61% unsound
        (A5); now the observed callers are real calls that really happened.

        The runtime and static answers are returned in SEPARATE sections,
        because they fail in opposite directions and a merged list would hide
        which kind of evidence each row is.
        """
        entry = self._method_entry(cls, method)
        run = self._run(build_sha)
        graph = run.evidence.runtime_edges
        by_key = {(v.cls, v.probe_idx): v for v in run.verdicts}
        subject = by_key.get((cls, entry.idx))

        callers = graph.callers_of(cls, entry.idx)[:200]
        rows: list[dict[str, Any]] = []
        for edge in callers:
            caller_verdict = by_key.get((edge.caller_cls, edge.caller_idx))
            rows.append(
                {
                    **self._edge_row(edge, endpoint="caller"),
                    "status": caller_verdict.status if caller_verdict else "UNKNOWN",
                    # The load-bearing bit: is the caller itself alive?
                    "isLive": bool(caller_verdict and caller_verdict.status == LIVE),
                    "verdictKnown": caller_verdict is not None,
                }
            )
        any_live = any(r["isLive"] for r in rows)

        ref = MethodRef(cls, entry.name, entry.desc)
        static_callers = [
            {
                "class": e.src.cls,
                "method": e.src.name,
                "desc": e.src.desc,
                "resolution": e.resolution,
                "semantics": e.semantics,
            }
            for e in self.engine.manifest.call_edges
            if e.dst == ref
        ]

        return {
            "buildSha": build_sha,
            "class": cls,
            "method": f"{entry.name}{entry.desc}",
            "idx": entry.idx,
            "verdict": subject.to_json() if subject else None,
            "runtime": {
                "source": "sampled runtime call-edge tier (SCOPE-v3)",
                "observedCallers": rows,
                "observedCallerCount": len(rows),
                "anyObservedCallerIsLive": any_live,
                "totalSampledObservations": sum(e.sampled_observations for e in callers),
                "edgesSampleRate": graph.uniform_sample_rate,
                "edgesSampleRates": self._rates(graph),
                "tierReported": graph.reported,
                "countSemantics": COUNT_SEMANTICS,
                "absenceNote": graph.caveat(),
            },
            "static": {
                "source": "manifest callEdges (build-time, whole-program scan)",
                "callers": static_callers,
                "callerCount": len(static_callers),
                "graphSemantics": STATIC_GRAPH_SEMANTICS,
            },
            "readBeforeDeleting": self._blast_guidance(rows, any_live, subject, graph),
        }

    def hot_paths(self, build_sha: str, *, limit: int = 20) -> dict[str, Any]:
        """The highest-count OBSERVED edges for a build.

        A ranking of relative volume, not a call total. The ordering is only
        meaningful among edges recorded at the same sample rate, so the rates
        in play are returned with it.
        """
        run_graph = self._graph(build_sha)
        edges = run_graph.hot_edges(limit)
        return {
            "buildSha": build_sha,
            "paths": [
                {
                    "caller": self._endpoint(edge.caller_cls, edge.caller_idx),
                    "callee": self._endpoint(edge.callee_cls, edge.callee_idx),
                    **self._counts(edge),
                    "windows": edge.windows,
                    "firstSeen": edge.first_seen.isoformat() if edge.first_seen else None,
                    "lastSeen": edge.last_seen.isoformat() if edge.last_seen else None,
                }
                for edge in edges
            ],
            "edgesSampleRate": run_graph.uniform_sample_rate,
            "edgesSampleRates": self._rates(run_graph),
            "tierReported": run_graph.reported,
            "countSemantics": COUNT_SEMANTICS,
            "rankingNote": (
                "Relative volume among SAMPLED observations. Edges recorded at different "
                "sample rates are not comparable, and an edge missing from this list may "
                "still be the hottest path in the build."
            ),
        }

    # -- edge helpers ----------------------------------------------------

    def _graph(self, build_sha: str) -> RuntimeCallGraph:
        return self.engine.evidence(build_sha).runtime_edges

    def _edge_query(
        self, build_sha: str, cls: str, method: str, *, inbound: bool, limit: int
    ) -> dict[str, Any]:
        entry = self._method_entry(cls, method)
        graph = self._graph(build_sha)
        edges = (
            graph.callers_of(cls, entry.idx) if inbound else graph.callees_of(cls, entry.idx)
        )[:limit]
        role = "caller" if inbound else "callee"
        return {
            "buildSha": build_sha,
            "class": cls,
            "method": f"{entry.name}{entry.desc}",
            "idx": entry.idx,
            "direction": "callers" if inbound else "callees",
            role + "s": [self._edge_row(e, endpoint=role) for e in edges],
            "observedEdgeCount": len(edges),
            "totalSampledObservations": sum(e.sampled_observations for e in edges),
            "edgesSampleRate": graph.uniform_sample_rate,
            "edgesSampleRates": self._rates(graph),
            "tierReported": graph.reported,
            "countSemantics": COUNT_SEMANTICS,
            "absenceNote": graph.caveat(),
        }

    def _edge_row(self, edge: EdgeAggregate, *, endpoint: str) -> dict[str, Any]:
        cls, idx = (
            (edge.caller_cls, edge.caller_idx)
            if endpoint == "caller"
            else (edge.callee_cls, edge.callee_idx)
        )
        return {
            **self._endpoint(cls, idx),
            **self._counts(edge),
            "windows": edge.windows,
            "firstSeen": edge.first_seen.isoformat() if edge.first_seen else None,
            "lastSeen": edge.last_seen.isoformat() if edge.last_seen else None,
        }

    @staticmethod
    def _counts(edge: EdgeAggregate) -> dict[str, Any]:
        """The count, its rate, and an estimate -- always together.

        `estimatedCalls` is None when the contributing windows used different
        rates (or declared none): scaling a mixed-rate sum by one of its rates
        would produce a number that looks precise and is not.
        """
        rate = edge.uniform_sample_rate
        return {
            "sampledObservations": edge.sampled_observations,
            "edgesSampleRate": rate,
            "estimatedCalls": (edge.sampled_observations * rate) if rate else None,
            "estimateBasis": (
                "sampledObservations * edgesSampleRate; order of magnitude only"
                if rate
                else "unavailable: contributing windows used different or undeclared "
                     "sample rates, so the sum has no single scale"
            ),
        }

    @staticmethod
    def _rates(graph: RuntimeCallGraph) -> dict[str, int]:
        return {
            str(rate or "undeclared"): windows
            for rate, windows in sorted(graph.sample_rates.items())
        }

    def _endpoint(self, cls: str, idx: int) -> dict[str, Any]:
        return {"class": cls, "idx": idx, "method": self._method_name(cls, idx)}

    def _method_entry(self, cls: str, method: str) -> MethodEntry:
        """Resolve `method` to the manifest entry that owns the probe index.

        Accepts `name` or `name(desc)`. An ambiguous bare name RAISES rather
        than picking an overload: silently answering about `pick(List)` when
        the caller meant `pick(String)` would hand them the blast radius of a
        different method.
        """
        entry = self.engine.manifest.klass(cls)
        if entry is None:
            raise ValueError(f"class {cls!r} is not in the manifest for this build")
        if "(" in method:
            name, _, desc = method.partition("(")
            desc = "(" + desc
            matches = [m for m in entry.methods if m.name == name and m.desc == desc]
        else:
            matches = [m for m in entry.methods if m.name == method]
        if not matches:
            raise ValueError(f"method {method!r} is not in the manifest for {cls}")
        if len(matches) > 1:
            overloads = ", ".join(f"{m.name}{m.desc}" for m in matches)
            raise ValueError(
                f"method {method!r} is overloaded in {cls}; pass name(descriptor) to pick "
                f"one of: {overloads}"
            )
        return matches[0]

    @staticmethod
    def _blast_guidance(
        rows: list[dict[str, Any]],
        any_live: bool,
        subject: Verdict | None,
        graph: RuntimeCallGraph,
    ) -> str:
        parts: list[str] = []
        if subject is not None and subject.status == LIVE:
            parts.append(
                "This method is itself LIVE. Do not delete it; the verdict's reasons say "
                "which positive observation proved it."
            )
        if any_live:
            live = [f"{r['class']}#{r['method']}" for r in rows if r["isLive"]][:5]
            parts.append(
                "At least one OBSERVED caller is itself LIVE (" + ", ".join(live) + "). A "
                "real, live code path reached this method in production. Deleting it breaks "
                "that path."
            )
        if parts:
            return " ".join(parts)
        if rows:
            return (
                f"{len(rows)} caller(s) were observed calling this method, but none of them "
                "is itself LIVE. The call really happened, so treat this as a cluster to "
                "review together rather than a single safe deletion -- and note that "
                "'caller not LIVE' is usually UNKNOWN, not dead."
            )
        if not graph.reported:
            return (
                "The runtime edge tier never reported for this build, so there is NO "
                "runtime evidence about callers either way. This is a configuration fact "
                "(`edgesEnabled` defaults to false), not an observation."
            )
        return (
            "No observed inbound edge. This is NOT evidence that nothing calls this method: "
            "the tier samples 1-in-N root entries and is depth-, per-root- and "
            "distinct-bounded with drop-on-full, so a hot path can legitimately show zero "
            "edges. Use the verdict and the static callers above, and note that absence "
            "here contributed nothing to that verdict."
        )

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
        strip_mask_missing = 0
        strip_mask_missing_windows = 0
        for w in windows:
            strip_mask_missing += w.agent_health.strip_mask_missing
            strip_mask_missing_windows += int(bool(w.agent_health.strip_mask_missing))
            transform_failures += w.agent_health.transform_failures
            ring_dropped += w.agent_health.ring_dropped
            degraded += int(w.degraded)
            clock_degraded += int(w.agent_health.clock_degraded)
            for reason, count in w.agent_health.classes_skipped.items():
                skipped[reason] = skipped.get(reason, 0) + count
        # SCOPE-v3 edge counters. Each one is a distinct way the graph lost an
        # edge, which is why they are reported per-reason rather than summed
        # into a single "lost" number.
        edges = {
            "windowsWithTierEnabled": 0,
            "sampledRoots": 0,
            "recorded": 0,
            "dropped": 0,
            "truncatedDepth": 0,
            "truncatedRoot": 0,
            "truncatedDistinct": 0,
            "tierFailures": 0,
            "tracesReaped": 0,
        }
        sample_rates: dict[int, int] = {}
        for w in windows:
            eh = w.agent_health.edges
            edges["windowsWithTierEnabled"] += int(eh.enabled)
            edges["sampledRoots"] += eh.sampled_roots
            edges["recorded"] += eh.recorded
            edges["dropped"] += eh.dropped
            edges["truncatedDepth"] += eh.truncated_depth
            edges["truncatedRoot"] += eh.truncated_root
            edges["truncatedDistinct"] += eh.truncated_distinct
            edges["tierFailures"] += eh.tier_failures
            edges["tracesReaped"] += eh.traces_reaped
            if eh.enabled or eh.sample_rate:
                sample_rates[eh.sample_rate] = sample_rates.get(eh.sample_rate, 0) + 1

        # BUG #22b. The trial's complaint was that an unclassified JVM produced
        # "0 windows stored" with a 403 and no explanation. Now the windows are
        # stored, so this is the place the user finds out what they are worth --
        # a count, the labels involved (so the `--allow-environments` fix is
        # obvious), and one sentence saying what it means for verdicts.
        non_production = [w for w in windows if not w.production]
        environments: dict[str, int] = {}
        for w in windows:
            environments[w.environment] = environments.get(w.environment, 0) + 1
        usable = sum(1 for w in windows if w.usable_as_death_evidence)

        result: dict[str, Any] = {
            "buildSha": build_sha,
            "windows": len(windows),
            "degradedWindows": degraded,
            "clockDegradedWindows": clock_degraded,
            "environments": dict(sorted(environments.items())),
            "nonProductionWindows": len(non_production),
            "nonProductionEnvironments": dict(
                sorted(
                    (
                        (label, sum(1 for w in non_production if w.environment == label))
                        for label in {w.environment for w in non_production}
                    )
                )
            ),
            "windowsUsableAsDeathEvidence": usable,
            "livenessEvidenceWindows": sum(
                1 for w in windows if w.usable_as_life_evidence
            ),
            "evidenceGate": self._environment_diagnosis(windows),
            "nonProductionNote": NON_PRODUCTION_MEANING,
            "transformFailuresTotal": transform_failures,
            "ringDroppedTotal": ring_dropped,
            "classesSkippedTotal": skipped,
            # Bug #18. Deliberately NOT folded into degradedWindows: a JVM
            # that could not determine its own install mask still produced
            # good evidence of LIFE in the same window.
            "stripMaskMissingTotal": strip_mask_missing,
            "stripMaskMissingWindows": strip_mask_missing_windows,
            "stripMaskMissingNote": (
                "the agent could not determine which probes it had installed and shipped "
                "an ALL-ZERO probesInstalled mask -- candidates are LOST, never invented; "
                "see the instrumentation-gaps query"
            ),
            "instances": sorted({w.instance_id for w in windows}),
            "note": (
                "degraded windows are usable as evidence of LIFE but never as "
                "evidence of death (CONTRACTS 2)"
            ),
            "edgeTier": {
                **edges,
                "edgesSampleRates": {
                    str(rate or "undeclared"): count
                    for rate, count in sorted(sample_rates.items())
                },
                "note": (
                    "edgeTierFailures is deliberately NOT `degraded` (CONTRACTS 2 v3): the "
                    "edge tier latching itself off leaves the coverage probes and tier-2 "
                    "timings in the same window valid. Every counter here is a way an edge "
                    "was lost, which is why absence of an edge is never evidence of death."
                ),
            },
        }
        if self.collector is not None:
            result["ingest"] = self.collector.health.snapshot()
        rejects = getattr(self.engine.store, "rejects", None)
        if rejects is not None:
            result["recentRejects"] = rejects(build_sha, 20)
        return result

    @staticmethod
    def _environment_diagnosis(windows: Sequence[Any]) -> str:
        """BUG #22b: the one line that tells a first-time user what to do next.

        Deliberately an explanation and not a status code. The trial user had
        to read the collector source to discover that `production`/`prod` were
        the only accepted labels; nobody should have to do that twice.
        """
        if not windows:
            return (
                "No window has been stored for this build. Nothing has reported yet, or "
                "every ingest was refused -- see rejectsByReason in the ingest counters."
            )
        non_production = [w for w in windows if not w.production]
        if not non_production:
            return (
                f"All {len(windows)} window(s) are production-classified and are usable as "
                "evidence (subject to the degraded and test-taint gates)."
            )
        labels = sorted({w.environment for w in non_production})
        # `unclassified` is OUR placeholder for "the window carried no label at
        # all", not something a JVM reported -- so it must never be offered as
        # a `--allow-environments` value. Allowlisting it would re-create C50:
        # every unlabelled JVM, CI included, would count as production.
        reported = [label for label in labels if label and label != UNCLASSIFIED]
        fix = (
            f"pass `--allow-environments {','.join(reported)}` if those labels really are "
            "production environments"
            if reported
            else (
                "these windows carry NO environment label at all, so there is nothing to "
                "allowlist -- set ax.environment=production on the JVMs you want "
                "conclusions from"
            )
        )
        if len(non_production) == len(windows):
            return (
                f"EVERY one of the {len(windows)} stored window(s) is NON-PRODUCTION "
                f"(labels: {labels}). Data IS arriving and being stored -- it is counted "
                "and its tier-2 timings and exception classes are queryable -- but none of "
                "it is evidence, so every verdict is UNKNOWN and no DEAD_CANDIDATE can "
                f"exist. Set ax.environment=production, or {fix}."
            )
        return (
            f"{len(non_production)} of {len(windows)} stored window(s) are non-production "
            f"(labels: {labels}) and contributed nothing to any verdict. To use them, "
            f"{fix}."
        )

    # -- exception classes (bug #24) -------------------------------------

    def exception_classes(
        self, build_sha: str, *, since_days: int = 7, limit: int = 20
    ) -> dict[str, Any]:
        """"Top exception classes for this build" -- BUG #24.

        The README promised *"what does it throw -- exception class names"* and
        shipped a scalar count: `Tier2Aggregator` kept `errorCounts[]` by class
        id, `ErrorIds` kept the name table, and both were dropped at the wire.
        This is the aggregate half of the answer; `hot_methods` carries the
        per-method half.

        Three numbers that are never collapsed into one, because the contract
        says a reader must not reconcile them:

            attributed    errors we can name a class for
            unattributed  `errors - attributed`; legal, and NOT an error
            errors        the unconditional total
        """
        since = datetime.now(tz=UTC) - timedelta(days=since_days)
        totals_fn = getattr(self.engine.store, "error_class_totals", None)
        if totals_fn is None:
            return {
                "buildSha": build_sha,
                "supported": False,
                "topClasses": [],
                "note": (
                    "This store does not implement Tier2ErrorStore, so no exception-class "
                    "breakdown is available. `errors` counts are unaffected."
                ),
            }
        payload = dict(totals_fn(build_sha, since, limit))
        payload["buildSha"] = build_sha
        payload["supported"] = True
        payload["sinceDays"] = since_days
        payload["note"] = ERROR_ATTRIBUTION_NOTE
        payload["reading"] = self._error_reading(payload)
        return payload

    @staticmethod
    def _error_reading(payload: dict[str, Any]) -> str:
        """Say out loud which of the three legal shapes this answer is in."""
        errors = int(payload.get("errors", 0) or 0)
        attributed = int(payload.get("attributed", 0) or 0)
        unattributed = int(payload.get("unattributed", 0) or 0)
        unresolved = int(payload.get("unresolvedIds", 0) or 0)
        if not errors:
            return "No errors were recorded in this window range."
        if not payload.get("typesAvailable"):
            return (
                f"{errors} error(s) recorded and exception TYPES ARE UNAVAILABLE for all "
                "of them -- the agent sent no errorsByClass breakdown (an older agent, or "
                "its id table was unavailable). This is not 'zero exception types'; it is "
                "'we have the count and not the names'."
            )
        parts = [f"{attributed} of {errors} error(s) are attributed to a named class"]
        if unattributed:
            parts.append(
                f"{unattributed} are UNATTRIBUTED and stay that way: the agent's table "
                "holds 254 classes with id 255 as an overflow bucket while `errors` is "
                "counted unconditionally, so the shortfall is expected and is never "
                "closed by inventing a class"
            )
        if unresolved:
            parts.append(
                f"{unresolved} of those arrived under an id that the window's own "
                "errorClasses table did not name, so they have no name to give"
            )
        return "; ".join(parts) + "."

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

    # -- suppressions (bug #28) ------------------------------------------

    def suppressions(self) -> dict[str, Any]:
        """Every active suppression rule and WHICH LIST it came from.

        BUG #28 ships a default list, and a default filter nobody can see is
        worse than the false positives it removes. So the whole set is
        enumerable -- in match order, with the origin, source file and line of
        every rule -- and `defaultsEnabled` says whether the defaults are on.
        """
        active: Suppressions = self.engine.suppressions
        user: Suppressions = getattr(self.engine, "user_suppressions", Suppressions(()))
        return {
            "rules": active.listing(),
            "count": len(active),
            "countsByOrigin": active.counts_by_origin(),
            "userRules": len(user),
            "defaultsEnabled": bool(self.engine.config.default_suppressions),
            "note": (
                "Matched in the order listed, first match wins, BEFORE any verdict is "
                "computed (CONTRACTS 5). User rules come first, so a user pattern always "
                "wins the attribution. A suppressed method is UNKNOWN and still appears in "
                "the output with a `suppressed:` reason naming the list it came from -- it "
                "is never silently omitted. Run with --no-default-suppressions to use the "
                "user's file alone."
            ),
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
