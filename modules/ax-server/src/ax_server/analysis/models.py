"""The verdict model -- CONTRACTS 4 plus the C51/C53 amendments.

CONTRACTS 4 is marked FROZEN. Two post-freeze findings force an amendment:

* C51 adds the eligibility class ``NOT_DYNAMICALLY_OBSERVABLE``. TOSEM 2022:
  "Primitive constants, custom exceptions, and single-instruction methods...
  are not part of the executable code in the bytecode, and cannot be covered
  dynamically." Counting those as "unobserved" is a correctness bug.
* C53 makes a proposal a standing claim rather than a snapshot, which needs
  ``first_proposed_at`` and ``revoked_reason``.

Both are recorded in ``ax_server.CONTRACTS_4_AMENDMENT``; CONTRACTS.md 4 needs
its version bumped to match.
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
]

Status = Literal["DEAD_CANDIDATE", "LIVE", "UNKNOWN", "NOT_DYNAMICALLY_OBSERVABLE"]

DEAD_CANDIDATE: Status = "DEAD_CANDIDATE"
LIVE: Status = "LIVE"
UNKNOWN: Status = "UNKNOWN"
NOT_DYNAMICALLY_OBSERVABLE: Status = "NOT_DYNAMICALLY_OBSERVABLE"


class EligibilityClass(StrEnum):
    """Whether a method CAN be observed dynamically at all (C51).

    ``DE_INSTRUMENTED`` must stay distinguishable from
    ``NOT_DYNAMICALLY_OBSERVABLE``: the first was observed and then had its
    probes stripped by tier-1b (C4), the second never had a distinct frame to
    observe in the first place.
    """

    OBSERVABLE = "OBSERVABLE"
    NOT_DYNAMICALLY_OBSERVABLE = "NOT_DYNAMICALLY_OBSERVABLE"
    DE_INSTRUMENTED = "DE_INSTRUMENTED"


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
    static_reachable: bool | None = None
    suppressed: bool = False
    public_api: bool = False
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
            "suppressed": self.suppressed,
            "publicApi": self.public_api,
            "eligibility": str(self.eligibility),
            "firstProposedAt": (
                self.first_proposed_at.isoformat() if self.first_proposed_at else None
            ),
            "revokedReason": self.revoked_reason,
            "probeIdx": self.probe_idx,
            "entryPointKind": self.entry_point_kind,
        }
