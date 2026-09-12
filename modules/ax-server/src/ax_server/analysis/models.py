"""The verdict model -- CONTRACTS 4 plus the C51/C53 amendments.

CONTRACTS 4 is marked FROZEN. Two post-freeze findings force an amendment:

* C51 adds the eligibility class ``NOT_DYNAMICALLY_OBSERVABLE``. TOSEM 2022:
  "Primitive constants, custom exceptions, and single-instruction methods...
  are not part of the executable code in the bytecode, and cannot be covered
  dynamically." Counting those as "unobserved" is a correctness bug.
* C53 makes a proposal a standing claim rather than a snapshot, which needs
  ``first_proposed_at`` and ``revoked_reason``.

Bug #18 forces a third: ``eligibility`` gains ``NO_PROBE_INSTALLED`` and the
verdict gains ``probe_installed`` / ``probe_install_mask_reported``. A method
whose probe index was never instrumented has a permanently-zero bit that
nothing could ever write -- and the two causes the manifest cannot express
(``frameEmissionUnsupported`` for this JVM's bytecode shape;
``ax.tier1.enabled=false``) mean this can only be known at runtime. It is an
ELIGIBILITY class, not a status: ``status`` keeps its four values.

All three are recorded in ``ax_server.CONTRACTS_4_AMENDMENT``; CONTRACTS.md 4
needs its version bumped to match.
"""

from dataclasses import dataclass, field
from datetime import datetime
from enum import StrEnum
from typing import Any, Literal

__all__ = [
    "DEAD_CANDIDATE",
    "EligibilityClass",
    "LIVE",
    "NOT_DYNAMICALLY_OBSERVABLE",
    "Status",
    "UNKNOWN",
    "MethodRef",
    "Verdict",
    "effective_eligibility",
]

Status = Literal["DEAD_CANDIDATE", "LIVE", "UNKNOWN", "NOT_DYNAMICALLY_OBSERVABLE"]

DEAD_CANDIDATE: Status = "DEAD_CANDIDATE"
LIVE: Status = "LIVE"
UNKNOWN: Status = "UNKNOWN"
NOT_DYNAMICALLY_OBSERVABLE: Status = "NOT_DYNAMICALLY_OBSERVABLE"


class EligibilityClass(StrEnum):
    """Whether a method CAN be observed dynamically at all (C51).

    Four classes, and every one of them exists because conflating it with
    another turns an instrumentation fact into false evidence of death:

    ``OBSERVABLE``
        The manifest declares a real probe frame and some JVM installed a
        probe at its index. An unset bit here is a real observation.
    ``NOT_DYNAMICALLY_OBSERVABLE``
        C51: constant-returning, compile-time-folded or single-instruction
        bodies. There was never a distinct frame to observe.
    ``DE_INSTRUMENTED``
        C4/Tier-1b: observed, then had its probes stripped by our OWN
        optimisation. Must stay distinguishable from the line above, or the
        optimisation becomes evidence against the code it optimised.
    ``NO_PROBE_INSTALLED``
        Bug #18: the manifest says the method IS observable, but no JVM ever
        installed a probe at its index -- ``ax.tier1.enabled=false``, a
        per-method ``frameEmissionUnsupported`` decision about this JVM's
        bytecode shape, or a class-file fallback. None of those is expressible
        in a manifest, so this class cannot be derived at build time; it comes
        from ``coverage[].probesInstalled``. **This is the case that was
        silently reading as dead.**
    """

    OBSERVABLE = "OBSERVABLE"
    NOT_DYNAMICALLY_OBSERVABLE = "NOT_DYNAMICALLY_OBSERVABLE"
    DE_INSTRUMENTED = "DE_INSTRUMENTED"
    NO_PROBE_INSTALLED = "NO_PROBE_INSTALLED"


def effective_eligibility(
    declared: EligibilityClass, probe_installed: bool | None
) -> EligibilityClass:
    """Combine the manifest's build-time class with the runtime install mask.

    ONE function, called by both the rule (for its audit trail) and the engine
    (for the verdict and the C55 rule-class keys), so the three cases cannot
    drift apart between the reason text and the field an operator filters on:

        manifest says it cannot be covered (C51)   -> NOT_DYNAMICALLY_OBSERVABLE
        our own tier-1b stripped the probe (C4)    -> DE_INSTRUMENTED
        observable, but NO JVM installed a probe   -> NO_PROBE_INSTALLED
        installed (or no mask reported at all)     -> the declared class

    `probe_installed is None` -- no mask reported for the class, i.e. a
    pre-#18 agent -- deliberately changes NOTHING. Abstaining keeps such a
    build exactly as it was rather than reclassifying every method in it on
    the strength of a producer's age.
    """
    if declared is not EligibilityClass.OBSERVABLE:
        return declared
    if probe_installed is False:
        return EligibilityClass.NO_PROBE_INSTALLED
    return declared


@dataclass(frozen=True, slots=True, order=True)
class MethodRef:
    """`(className, methodName, descriptor)` -- the identity from CONTRACTS 1.

    Never a hash of runtime class bytes (A14 defect 1).
    """

    cls: str
    name: str
    desc: str

    @property
    def key(self) -> str:
        """The `C#m(D)` form used by `callEdges` in CONTRACTS 1."""
        return f"{self.cls}#{self.name}{self.desc}"

    @classmethod
    def parse(cls, key: str) -> "MethodRef":
        if "#" not in key:
            raise ValueError(f"not a method key: {key!r}")
        owner, rest = key.split("#", 1)
        if "(" in rest:
            name, desc = rest.split("(", 1)
            return cls(owner, name, "(" + desc)
        return cls(owner, rest, "")

    def __str__(self) -> str:
        return self.key


@dataclass(frozen=True, slots=True)
class Verdict:
    """CONTRACTS 4 + amendment. `UNKNOWN` is the default everywhere."""

    cls: str
    method: str
    desc: str
    status: Status
    reasons: list[str] = field(default_factory=list)
    window_days: int = 0
    phases_covered: list[str] = field(default_factory=list)
    phases_missing: list[str] = field(default_factory=list)
    last_seen: datetime | None = None
    #: OVER-approximate: the manifest call graph, measured missing 61% of
    #: methods that actually execute (A5). True / False / None, where None is
    #: "unresolved" and must never be read as False.
    static_reachable: bool | None = None
    suppressed: bool = False
    public_api: bool = False
    # -- SCOPE-v3 runtime call graph -----------------------------------
    # UNDER-approximate, and kept a SEPARATE FIELD from static_reachable on
    # purpose. The two have opposite error modes (see runtime_edges.py), so a
    # single merged "reachable" boolean would be unreadable: the caller could
    # not tell whether True meant "a call site exists in the bytecode" or "a
    # call happened in production last Tuesday".
    #
    # Domain is True | None. There is NO False, because the graph is sampled
    # and absence is never evidence (CONTRACTS 2 v3).
    runtime_reachable: bool | None = None
    runtime_inbound_edges: int = 0
    runtime_outbound_edges: int = 0
    #: RAW observations in sampled traces. Not calls. Never quote without the
    #: sample rate below.
    runtime_inbound_observations: int = 0
    runtime_edges_sample_rate: int | None = None
    #: Whether the edge tier reported at all for this build. False => the
    #: zeroes above mean "no data", not "no callers".
    runtime_edges_reported: bool = False
    # -- installed-probe mask (bug #18) --------------------------------
    # Domain is True | False | None and the None is load bearing:
    #   True   a probe exists at this index, so an unset bit is an observation
    #   False  no JVM ever installed one -- the bit is zero because nothing
    #          can write it. NEVER death evidence.
    #   None   no agent reported a mask for the class (a pre-#18 producer);
    #          nothing is known either way.
    probe_installed: bool | None = None
    #: Whether any window declared `probesInstalled` for this class. False =>
    #: `probe_installed` is None because nobody spoke, not because of a
    #: measurement.
    probe_install_mask_reported: bool = False
    # -- amendment ----------------------------------------------------
    eligibility: EligibilityClass = EligibilityClass.NOT_DYNAMICALLY_OBSERVABLE
    first_proposed_at: datetime | None = None
    revoked_reason: str | None = None
    probe_idx: int | None = None
    entry_point_kind: str = "none"

    @property
    def ref(self) -> MethodRef:
        return MethodRef(self.cls, self.method, self.desc)

    def to_json(self) -> dict[str, Any]:
        return {
            "class": self.cls,
            "method": self.method,
            "desc": self.desc,
            "status": self.status,
            "reasons": list(self.reasons),
            "windowDays": self.window_days,
            "phasesCovered": list(self.phases_covered),
            "phasesMissing": list(self.phases_missing),
            "lastSeen": self.last_seen.isoformat() if self.last_seen else None,
            "staticReachable": self.static_reachable,
            # Deliberately a different key, never folded into staticReachable.
            "runtimeReachable": self.runtime_reachable,
            "runtimeInboundEdges": self.runtime_inbound_edges,
            "runtimeOutboundEdges": self.runtime_outbound_edges,
            "runtimeInboundSampledObservations": self.runtime_inbound_observations,
            "edgesSampleRate": self.runtime_edges_sample_rate,
            "runtimeEdgesReported": self.runtime_edges_reported,
            "suppressed": self.suppressed,
            "publicApi": self.public_api,
            # Bug #18. A tri-state on the wire too: null means no mask was
            # reported, false means no probe was ever installed at this index
            # and its zero bit is therefore SILENCE, not an observation.
            "probeInstalled": self.probe_installed,
            "probeInstallMaskReported": self.probe_install_mask_reported,
            "eligibility": str(self.eligibility),
            "firstProposedAt": (
                self.first_proposed_at.isoformat() if self.first_proposed_at else None
            ),
            "revokedReason": self.revoked_reason,
            "probeIdx": self.probe_idx,
            "entryPointKind": self.entry_point_kind,
        }
