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
from ax_server.analysis.manifest import ClassEntry, Manifest, MethodEntry
from ax_server.analysis.models import DEAD_CANDIDATE, MethodRef, Verdict
from ax_server.analysis.phases import Interval, PhaseCalendar, PhaseCoverage
from ax_server.analysis.proposals import ProposalLedger, SqliteProposalLedger
from ax_server.analysis.ratelimit import DEFAULT_PROPOSALS_PER_DAY, ProposalRateLimiter
from ax_server.analysis.reachability import compute_reachability, entry_point_kinds
from ax_server.analysis.rules import MethodFacts, evaluate, first_blocking_reason
from ax_server.analysis.suppression import Suppressions
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


@dataclass(frozen=True, slots=True)
class Evidence:
    """Build-wide evidence, gathered once per run."""

    build_sha: str
    usable_windows: tuple[Window, ...]
    excluded_windows: tuple[Window, ...]
    phase_coverage: PhaseCoverage

    @property
    def window_days(self) -> int:
        return self.phase_coverage.window_days


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
            self.suppressions = suppressions
        elif self.config.suppression_path is not None:
            self.suppressions = Suppressions.from_file(self.config.suppression_path)
        else:
            self.suppressions = Suppressions(())
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
        return Evidence(build_sha, tuple(usable), tuple(excluded), coverage)

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

        proposal = self.ledger.get(build_sha, ref)
        keys = efp_keys(entry_kind, str(method.eligibility))
        disabled_key = self.efp.disabled(keys)

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
            efp_disabled_key=disabled_key,
            rate_limit_reason=None,
            min_window_days=self.config.min_window_days,
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
            suppressed=suppression is not None,
            public_api=klass.is_public_api,
            eligibility=method.eligibility,
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
