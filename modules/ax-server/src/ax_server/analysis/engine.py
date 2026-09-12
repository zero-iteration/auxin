"""gt-analysis: turn stored observations + the static manifest into verdicts.

This module NEVER deletes anything and never asks anyone else to. It emits
`Verdict` objects, and `DEAD_CANDIDATE` means "a human should look", not "this
is dead".

Honest accuracy expectation (C57): the closest published analogue to this exact
architecture -- CQSE, static reachability from entry points plus a runtime list
of loaded classes, applied iteratively -- measured **72% precision**, of which
"about half could actually be removed", i.e. roughly **1 in 3 flagged items
genuinely deletable**. JShrink's held-out test: ~15% still break on unseen
executions even with static and dynamic evidence combined. Nothing here should
be described as better than that.

Two passes:

* ``derive()``  -- pure read. Re-derives every verdict from scratch (C53) and
  reads the proposal ledger for provenance. Safe to call on every API request.
* ``reconcile()`` -- derive, then persist: confirm standing proposals, revoke
  the ones that stopped being candidates, and account new reports against the
  effective-false-positive counters.
"""

import logging
from collections.abc import Callable
from dataclasses import dataclass, field, replace
from datetime import UTC, datetime
from pathlib import Path

from ax_server.analysis.efp import EfpTracker, efp_keys
from ax_server.analysis.instrumentation import InstalledProbes, load_installed_probes
from ax_server.analysis.manifest import ClassEntry, Manifest, MethodEntry
from ax_server.analysis.models import (
    DEAD_CANDIDATE,
    EligibilityClass,
    MethodRef,
    Verdict,
    effective_eligibility,
)
from ax_server.analysis.phases import Interval, PhaseCalendar, PhaseCoverage
from ax_server.analysis.proposals import ProposalLedger, SqliteProposalLedger
from ax_server.analysis.ratelimit import DEFAULT_PROPOSALS_PER_DAY, ProposalRateLimiter
from ax_server.analysis.reachability import compute_reachability, entry_point_kinds
from ax_server.analysis.rules import MethodFacts, evaluate, first_blocking_reason
from ax_server.analysis.runtime_edges import RuntimeCallGraph, load_runtime_call_graph
from ax_server.analysis.suppression import Suppressions, compose_suppressions
from ax_server.collector.classification import NON_PRODUCTION_MEANING
from ax_server.store.bitset import is_set
from ax_server.store.models import Window
from ax_server.store.port import Store

__all__ = ["AnalysisConfig", "AnalysisEngine", "AnalysisRun", "Evidence"]

log = logging.getLogger("ax.analysis")


def _utcnow() -> datetime:
    return datetime.now(tz=UTC)


@dataclass(frozen=True, slots=True)
class AnalysisConfig:
    suppression_path: Path | None = None
    phase_calendar: PhaseCalendar = field(default_factory=PhaseCalendar.default)
    min_window_days: int = 0
    proposals_per_day: int = DEFAULT_PROPOSALS_PER_DAY
    #: BUG #28: ship the compiler/Lombok suppression list by default. Set
    #: False (`--no-default-suppressions`) to run with the user's file alone.
    #: The set is always enumerable via `AnalysisEngine.suppressions.listing()`
    #: -- an invisible filter is the failure mode this flag exists to avoid.
    default_suppressions: bool = True


@dataclass(frozen=True, slots=True)
class Evidence:
    """Build-wide evidence, gathered once per run."""

    build_sha: str
    usable_windows: tuple[Window, ...]
    excluded_windows: tuple[Window, ...]
    phase_coverage: PhaseCoverage
    #: SCOPE-v3 observed call graph. Loaded once per run: the presence sets
    #: are one query and every method's rule needs them.
    runtime_edges: RuntimeCallGraph = field(default_factory=RuntimeCallGraph)
    #: Bug #18: which probe indices were ACTUALLY INSTALLED, OR-merged across
    #: pods and windows. One query per run, and every method's rule needs it.
    #: An empty one means "no mask reported", never "nothing installed".
    instrumentation: InstalledProbes = field(default_factory=InstalledProbes)

    @property
    def window_days(self) -> int:
        return self.phase_coverage.window_days

    @property
    def all_windows(self) -> tuple[Window, ...]:
        return self.usable_windows + self.excluded_windows

    @property
    def non_production_windows(self) -> tuple[Window, ...]:
        """BUG #22b: windows that were STORED and are evidence for nothing.

        Reported as their own bucket rather than inside `excluded_windows`,
        because "we have no data" and "we have data from a JVM you have not
        told us is production" are different problems with different fixes,
        and only the second one is fixed by a CLI flag.
        """
        return tuple(w for w in self.all_windows if not w.production)

    @property
    def non_production_environments(self) -> dict[str, int]:
        """`label -> window count`, so the fix is visibly one flag away."""
        out: dict[str, int] = {}
        for w in self.non_production_windows:
            out[w.environment] = out.get(w.environment, 0) + 1
        return dict(sorted(out.items()))

    def evidence_gate_note(self) -> str:
        """The one-line explanation of what the window counts mean for verdicts.

        A first-time user with an unclassified JVM must be able to read ONE
        sentence and understand why their data is arriving and not counting.
        Before bug #22b they got a 403 and no data at all.
        """
        if not self.all_windows:
            return (
                "No windows have been stored for this build at all. Nothing has reported "
                "yet, or every ingest was rejected -- check the collector's "
                "rejectsByReason counters."
            )
        if not self.non_production_windows:
            return (
                f"All {len(self.all_windows)} stored window(s) are production-classified. "
                f"{len(self.usable_windows)} are usable as death evidence; the rest were "
                "excluded as degraded or test-tainted."
            )
        labels = ", ".join(
            f"{label!r} x{count}" for label, count in self.non_production_environments.items()
        )
        if not self.usable_windows:
            return (
                f"{len(self.non_production_windows)} of {len(self.all_windows)} stored "
                f"window(s) are NOT production-classified ({labels}) and NO window is "
                "usable as death evidence, so every verdict here is UNKNOWN and no "
                "DEAD_CANDIDATE can be produced. Data IS arriving and being stored -- it "
                "is simply not evidence yet. " + NON_PRODUCTION_MEANING
            )
        return (
            f"{len(self.non_production_windows)} of {len(self.all_windows)} stored "
            f"window(s) are NOT production-classified ({labels}) and contributed nothing "
            f"to any verdict; {len(self.usable_windows)} production window(s) did. "
            + NON_PRODUCTION_MEANING
        )


@dataclass(frozen=True, slots=True)
class AnalysisRun:
    build_sha: str
    artifact: str
    at: datetime
    verdicts: tuple[Verdict, ...]
    evidence: Evidence
    revoked: tuple[tuple[MethodRef, str], ...] = ()

    def by_status(self, status: str) -> tuple[Verdict, ...]:
        return tuple(v for v in self.verdicts if v.status == status)

    def summary(self) -> dict[str, object]:
        counts: dict[str, int] = {}
        for v in self.verdicts:
            counts[v.status] = counts.get(v.status, 0) + 1
        return {
            "buildSha": self.build_sha,
            "artifact": self.artifact,
            "at": self.at.isoformat(),
            "counts": counts,
            "windowDays": self.evidence.window_days,
            "phasesCovered": list(self.evidence.phase_coverage.covered),
            "phasesMissing": list(self.evidence.phase_coverage.missing),
            "usableWindows": len(self.evidence.usable_windows),
            "excludedWindows": len(self.evidence.excluded_windows),
            # BUG #22b. The run summary is where a first-time user looks after
            # pointing an agent at the collector, so "your data arrived, here
            # is why it is not evidence" has to be legible HERE and not only
            # in a log line nobody reads.
            "windows": {
                "stored": len(self.evidence.all_windows),
                "usableAsDeathEvidence": len(self.evidence.usable_windows),
                "excluded": len(self.evidence.excluded_windows),
                "nonProduction": len(self.evidence.non_production_windows),
                "nonProductionEnvironments": self.evidence.non_production_environments,
                "degraded": sum(1 for w in self.evidence.all_windows if w.degraded),
                "testTainted": sum(
                    1 for w in self.evidence.all_windows if w.test_tainted
                ),
                "note": self.evidence.evidence_gate_note(),
            },
            "runtimeEdges": {
                "reported": self.evidence.runtime_edges.reported,
                "tierArmed": self.evidence.runtime_edges.armed,
                "methodsWithObservedInbound": len(self.evidence.runtime_edges.inbound),
                "methodsWithObservedOutbound": len(self.evidence.runtime_edges.outbound),
                "edgesSampleRates": {
                    str(rate or "undeclared"): windows
                    for rate, windows in sorted(self.evidence.runtime_edges.sample_rates.items())
                },
                "note": self.evidence.runtime_edges.caveat(),
            },
            # Bug #18: a misconfigured deployment (tier-1 off, or widespread
            # frame-emission skips) must be VISIBLE here instead of looking
            # like a codebase full of dead code -- or, now that the gate
            # exists, instead of looking like a codebase with suspiciously
            # few candidates and no explanation.
            "instrumentation": {
                "maskReported": self.evidence.instrumentation.reported,
                "maskSupportedByStore": self.evidence.instrumentation.supported,
                "classesWithMask": self.evidence.instrumentation.classes_reported,
                "observableButNeverInstrumented": sum(
                    1
                    for v in self.verdicts
                    if v.eligibility is EligibilityClass.NO_PROBE_INSTALLED
                ),
                "stripMaskMissingTotal": sum(
                    w.agent_health.strip_mask_missing for w in self.evidence.usable_windows
                ) + sum(
                    w.agent_health.strip_mask_missing for w in self.evidence.excluded_windows
                ),
                "note": self.evidence.instrumentation.caveat(),
            },
            "revoked": [[str(ref), why] for ref, why in self.revoked],
            "precisionPosture": (
                "false-negative-biased; do not quote a precision number. Closest "
                "published analogue (CQSE) measured 72% precision with ~1 in 3 "
                "flagged items genuinely removable (C57)."
            ),
        }


class AnalysisEngine:
    def __init__(
        self,
        store: Store,
        manifest: Manifest,
        *,
        config: AnalysisConfig | None = None,
        suppressions: Suppressions | None = None,
        ledger: ProposalLedger | None = None,
        efp: EfpTracker | None = None,
        limiter: ProposalRateLimiter | None = None,
        clock: Callable[[], datetime] = _utcnow,
    ) -> None:
        self.store = store
        self.manifest = manifest
        self.config = config or AnalysisConfig()
        if suppressions is not None:
            user_suppressions = suppressions
        elif self.config.suppression_path is not None:
            user_suppressions = Suppressions.from_file(self.config.suppression_path)
        else:
            user_suppressions = Suppressions(())
        # BUG #28: the defaults are layered here, in ONE place, so the CLI, the
        # MCP server and every test see the same rule set. User rules go first
        # (first-match-wins), so a user line always wins the attribution and
        # the defaults can only ever add coverage the user's file did not have.
        self.suppressions = compose_suppressions(
            user_suppressions,
            manifest=manifest,
            include_defaults=self.config.default_suppressions,
        )
        #: What the user actually wrote, kept separate so `--list-suppressions`
        #: can show the two layers apart.
        self.user_suppressions = user_suppressions
        self.ledger = ledger or SqliteProposalLedger()
        self.efp = efp or EfpTracker()
        self.limiter = limiter or ProposalRateLimiter(
            self.ledger, per_day=self.config.proposals_per_day, clock=clock
        )
        self._clock = clock
        self.reachability = compute_reachability(manifest)
        self._entry_kinds = entry_point_kinds(manifest)

    def close(self) -> None:
        """Release the ledger and EFP connections. Tests and short-lived CLIs."""
        for owned in (self.ledger, self.efp):
            closer = getattr(owned, "close", None)
            if closer is not None:
                closer()

    # -- evidence --------------------------------------------------------

    def evidence(self, build_sha: str) -> Evidence:
        windows = self.store.observed_windows(build_sha)
        usable = [w for w in windows if w.usable_as_death_evidence]
        excluded = [w for w in windows if not w.usable_as_death_evidence]
        intervals: list[Interval] = [(w.start, w.end) for w in usable]
        coverage = self.config.phase_calendar.evaluate(intervals)
        return Evidence(
            build_sha,
            tuple(usable),
            tuple(excluded),
            coverage,
            load_runtime_call_graph(self.store, build_sha),
            load_installed_probes(self.store, build_sha),
        )

    # -- derivation ------------------------------------------------------

    def derive(self, build_sha: str | None = None) -> AnalysisRun:
        """Re-derive every verdict from scratch. No writes. C53."""
        sha = build_sha or self.manifest.build_sha
        now = self._clock()
        ev = self.evidence(sha)
        self.limiter.begin_run()

        verdicts: list[Verdict] = []
        for klass, method in self._sorted_methods():
            verdicts.append(self._verdict(sha, klass, method, ev, now))
        return AnalysisRun(sha, self.manifest.artifact, now, tuple(verdicts), ev)

    def reconcile(self, build_sha: str | None = None) -> AnalysisRun:
        """Derive, then update the standing-claim ledger (C53) and C55 counters."""
        run = self.derive(build_sha)
        now = run.at
        candidates = {v.ref for v in run.verdicts if v.status == DEAD_CANDIDATE}
        revoked: list[tuple[MethodRef, str]] = []

        for verdict in run.verdicts:
            ref = verdict.ref
            if verdict.status == DEAD_CANDIDATE:
                existing = self.ledger.get(run.build_sha, ref)
                is_new = existing is None or not existing.active
                self.ledger.confirm(run.build_sha, run.artifact, ref, now)
                if is_new:
                    self.limiter.consume(run.artifact, when=now)
                    self.efp.record_report(
                        *efp_keys(verdict.entry_point_kind, str(verdict.eligibility))
                    )
                    log.info(
                        "NEW dead candidate proposed %s (entryPointKind=%s)",
                        ref, verdict.entry_point_kind,
                    )

        for proposal in self.ledger.active(run.build_sha):
            if proposal.ref in candidates:
                continue
            reason = self._revocation_reason(run, proposal.ref)
            self.ledger.revoke(run.build_sha, proposal.ref, now, reason)
            revoked.append((proposal.ref, reason))
            log.info("REVOKED dead candidate %s: %s", proposal.ref, reason)

        # Re-derive so the returned verdicts carry the fresh ledger provenance.
        final = self.derive(build_sha)
        return AnalysisRun(
            final.build_sha, final.artifact, final.at, final.verdicts, final.evidence,
            tuple(revoked),
        )

    # -- per-method ------------------------------------------------------

    def verdicts_for_class(self, build_sha: str, cls: str) -> list[Verdict]:
        entry = self.manifest.klass(cls)
        if entry is None:
            return []
        now = self._clock()
        ev = self.evidence(build_sha)
        self.limiter.begin_run()
        return [self._verdict(build_sha, entry, m, ev, now) for m in entry.methods]

    def _sorted_methods(self) -> list[tuple[ClassEntry, MethodEntry]]:
        # CONTRACTS 1 assigns idx by sorting (class, name, desc); we walk in
        # the same order so a rate-limited run is deterministic.
        out = [(c, m) for c, m in self.manifest.iter_methods()]
        out.sort(key=lambda pair: (pair[0].name, pair[1].name, pair[1].desc))
        return out

    def _verdict(
        self,
        build_sha: str,
        klass: ClassEntry,
        method: MethodEntry,
        ev: Evidence,
        now: datetime,
    ) -> Verdict:
        ref = MethodRef(klass.name, method.name, method.desc)
        probes = self.store.coverage(build_sha, klass.name) or b""
        observed = is_set(probes, method.idx) if method.idx >= 0 else False
        last_seen = self.store.last_seen(build_sha, klass.name, method.idx) if observed else None
        first_seen = self.store.first_seen(build_sha, klass.name, method.idx) if observed else None
        loaded = self.store.class_loaded_ever(build_sha, klass.name)
        static = self.reachability.classify(ref)
        suppression = self.suppressions.match(ref)
        entry_kind = self._entry_kinds.get(klass.name, "none")

        # Bug #18: the tri-state install mask. `None` (no window reported a
        # mask for this class) leaves every downstream decision exactly as it
        # was before #18 -- abstaining, not guessing in either direction.
        installed = ev.instrumentation.installed(klass.name, method.idx)
        eligibility = effective_eligibility(method.eligibility, installed)

        proposal = self.ledger.get(build_sha, ref)
        # Keyed on the EFFECTIVE class, so "observable but never instrumented"
        # is its own C55 rule class and cannot hide inside OBSERVABLE's rate.
        keys = efp_keys(entry_kind, str(eligibility))
        disabled_key = self.efp.disabled(keys)

        # SCOPE-v3. Presence is read from the eagerly loaded sets; the counts
        # are fetched only when there is something to count, and they never
        # participate in the death argument -- see rules.py clause 8b.
        inbound = ev.runtime_edges.inbound_evidence(klass.name, method.idx)
        outbound = ev.runtime_edges.outbound_evidence(klass.name, method.idx)

        facts = MethodFacts(
            ref=ref,
            method=method,
            klass=klass,
            observed=observed,
            first_seen=first_seen,
            last_seen=last_seen,
            class_loaded=loaded,
            static_reachable=static,
            suppression=suppression,
            phase_coverage=ev.phase_coverage,
            usable_windows=len(ev.usable_windows),
            excluded_windows=len(ev.excluded_windows),
            entry_point_kind=entry_kind,
            probe_installed=installed,
            efp_disabled_key=disabled_key,
            rate_limit_reason=None,
            min_window_days=self.config.min_window_days,
            runtime_inbound_edges=inbound.observed_edges,
            runtime_outbound_edges=outbound.observed_edges,
            runtime_inbound_observations=inbound.sampled_observations,
            runtime_edges_sample_rate=(
                inbound.uniform_sample_rate or ev.runtime_edges.uniform_sample_rate
            ),
            runtime_edges_reported=ev.runtime_edges.reported,
        )
        outcome = evaluate(facts)

        # The C54 cap applies only to NEW proposals, and only to a verdict the
        # rule already decided. Asking the limiter first would mean restating
        # the correctness rule here, where it could silently drift.
        if outcome.status == DEAD_CANDIDATE and (proposal is None or not proposal.active):
            decision = self.limiter.check(self.manifest.artifact, when=now)
            if decision.allowed:
                self.limiter.consume(self.manifest.artifact, when=now)
            else:
                outcome = evaluate(replace(facts, rate_limit_reason=decision.reason))

        return Verdict(
            cls=klass.name,
            method=method.name,
            desc=method.desc,
            status=outcome.status,
            reasons=outcome.reasons,
            window_days=ev.window_days,
            phases_covered=list(ev.phase_coverage.covered),
            phases_missing=list(ev.phase_coverage.missing),
            last_seen=last_seen,
            static_reachable=static,
            # Two fields, never one. `static` over-approximates (and A5 says
            # it also misses 61% of what executes); the runtime graph
            # under-approximates. A merged boolean would be worse than either.
            runtime_reachable=facts.runtime_reachable,
            runtime_inbound_edges=inbound.observed_edges,
            runtime_outbound_edges=outbound.observed_edges,
            runtime_inbound_observations=inbound.sampled_observations,
            runtime_edges_sample_rate=facts.runtime_edges_sample_rate,
            runtime_edges_reported=ev.runtime_edges.reported,
            suppressed=suppression is not None,
            public_api=klass.is_public_api,
            probe_installed=installed,
            probe_install_mask_reported=ev.instrumentation.reported_for(klass.name),
            eligibility=eligibility,
            first_proposed_at=proposal.first_proposed_at if proposal and proposal.active else None,
            revoked_reason=(
                proposal.revoked_reason if proposal and not proposal.active else None
            ),
            probe_idx=method.idx,
            entry_point_kind=entry_kind,
        )

    def _revocation_reason(self, run: AnalysisRun, ref: MethodRef) -> str:
        for verdict in run.verdicts:
            if verdict.ref == ref:
                why = first_blocking_reason(verdict.reasons) or "clause no longer satisfied"
                return f"no longer a candidate ({verdict.status}): {why}"
        return "method no longer present in the manifest"
