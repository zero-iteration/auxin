"""C50.1 -- JVM production classification, FAIL CLOSED.

    "The agent must refuse to report from any JVM not explicitly
    production-classified. Explicit allowlist, fail-closed (absent
    classification => do not report)."  -- VALIDATION.md C50

The agent enforces this at its end. The collector enforces it again here,
because a server that trusts its clients to police themselves does not have a
safety property, it has a convention.

CONTRACTS 2 does not define a classification field (it predates C50). We accept
several spellings additively -- the frozen fields are untouched -- and treat a
body with none of them as unclassified.

>>> BUG #22b: the gate was a CLIFF, and the cliff was the adoption blocker
-----------------------------------------------------------------------
Verbatim from the field trial on a 690-class Spring Boot service:

    "With `ax.environment` unset, the agent sets `livenessEvidence=false` and
    the collector 403s every window (`not_production_classified`). The
    allowlist is hardcoded (`production`, `prod`) in `classification.py` with
    no CLI flag. I got 0 windows stored and had to mislabel a laptop as
    production to see anything. This is the single biggest barrier to anyone
    trying it."

Rejecting the window never protected anything that storing it does not also
protect. The safety property C50 buys is *"a test JVM's coverage can never be
read as evidence that code is alive"* -- and that property lives in what a
window is allowed to be EVIDENCE FOR, not in whether the row exists. So the
default is now **store and mark**:

    classified production      -> production=True,  livenessEvidence=True
    anything else / unset      -> production=False, livenessEvidence=False,
                                  STORED, COUNTED, and evidence for NOTHING

`--allow-environments a,b,c` extends the allowlist; `--reject-unclassified`
restores the old 403 for anyone who wants the cliff back. What is deliberately
NOT configurable is the meaning of `production=False`: such a window can never
merge coverage, can never mark a class loaded, and can never create or revoke a
`DEAD_CANDIDATE`. A test pins each of those.
"""

from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from typing import Any

__all__ = [
    "DEFAULT_PRODUCTION_ENVIRONMENTS",
    "NON_PRODUCTION_MEANING",
    "UNCLASSIFIED",
    "Classification",
    "EnvironmentPolicy",
    "classify_payload",
    "parse_environments",
]

#: The environment labels accepted as production BY DEFAULT. Everything else --
#: including the empty string, "unclassified", "staging", "ci" -- is accepted
#: but marked `livenessEvidence=false` (bug #22b), or refused outright under
#: `--reject-unclassified`. Extend with `--allow-environments a,b,c`.
DEFAULT_PRODUCTION_ENVIRONMENTS: tuple[str, ...] = ("production", "prod")

#: The ONE-LINE explanation a first-time user needs, quoted verbatim wherever a
#: non-production window count is reported. Bug #22b's whole point is that
#: "data arrived and is not evidence" must be a sentence the user can read, not
#: a 403 they have to reverse-engineer.
NON_PRODUCTION_MEANING = (
    "Non-production windows ARE stored and counted, but they are evidence for nothing: "
    "their coverage is never merged, they never mark a class loaded, and they can neither "
    "create nor revoke a DEAD_CANDIDATE. C50 exists so a test JVM's coverage can never be "
    "read as proof that code is alive. Set ax.environment=production on the JVMs you want "
    "to draw conclusions from, or pass --allow-environments with your own labels."
)


def parse_environments(spec: str | None) -> tuple[str, ...]:
    """Parse a `--allow-environments a,b,c` value into normalised labels.

    Empty entries are dropped rather than becoming an allowlist entry that
    matches the empty string -- `--allow-environments "prod,"` must not quietly
    make an unlabelled JVM production.
    """
    if not spec:
        return ()
    return tuple(part.strip().lower() for part in spec.split(",") if part.strip())

#: Where we look for the label, in priority order.
#:
#: >>> BUG #17, SECOND OCCURRENCE. `agentHealth.environment` is the CANONICAL
#: spelling, pinned by CONTRACTS 2 ("Accept no synonyms in new code; the
#: collector may keep tolerant aliases for one version"), and it is what the
#: real agent emits -- `ax-agent`'s `Batch.java` writes `environment` INSIDE
#: `agentHealth`. This tuple listed only the aliases, so `classify_payload`
#: returned None for every window the real agent sends, and the C50 gate
#: rejected all of them 403 `not_production_classified`: 100% data loss,
#: reported as a misconfigured JVM. Exactly the bug #17 shape -- two
#: components, each verified against a stand-in for the other, since every
#: server fixture used `jvmClassification` and the agent's smoke listener
#: accepts any body.
#:
#: The canonical path goes FIRST; the aliases stay because dropping them would
#: break every existing fixture and deployment, and they are what "tolerant
#: for one version" means. The allowlist and the fail-closed default are
#: untouched: this adds a place to LOOK, never a way to pass.
_ENV_KEYS: tuple[tuple[str, ...], ...] = (
    ("agentHealth", "environment"),
    ("jvmClassification", "env"),
    ("jvmClassification", "environment"),
    ("environment",),
    ("env",),
)

UNCLASSIFIED = "unclassified"


@dataclass(frozen=True, slots=True)
class Classification:
    environment: str
    production: bool
    reason: str


class EnvironmentPolicy:
    """Decides whether a reporting JVM may be used as liveness evidence."""

    def __init__(
        self,
        production_environments: Sequence[str] = DEFAULT_PRODUCTION_ENVIRONMENTS,
        *,
        allow_unclassified: bool = False,
        allow_environments: Iterable[str] = (),
        reject_non_production: bool = False,
    ) -> None:
        # `allow_environments` EXTENDS the allowlist (`--allow-environments`,
        # bug #22b) rather than replacing it, so a user who adds "prod-eu"
        # cannot accidentally drop "production" on the way.
        self._allowed = {e.strip().lower() for e in production_environments}
        self._allowed |= {e.strip().lower() for e in allow_environments if e.strip()}
        # Escape hatch for local development ONLY. Turning this on in a real
        # deployment reintroduces C50: CI JVMs would count as production.
        self._allow_unclassified = allow_unclassified
        # Bug #22b: the OLD default, kept as an opt-in. False means a
        # non-production window is stored and marked; True means it is refused
        # 403 at ingest, which loses the coverage bits with it.
        self._reject_non_production = reject_non_production

    @property
    def allowed(self) -> frozenset[str]:
        return frozenset(self._allowed)

    @property
    def allow_unclassified(self) -> bool:
        return self._allow_unclassified

    @property
    def reject_non_production(self) -> bool:
        """`--reject-unclassified`: refuse the window instead of marking it."""
        return self._reject_non_production

    def classify(self, payload: Mapping[str, Any]) -> Classification:
        env = classify_payload(payload)
        if env is None:
            if self._allow_unclassified:
                return Classification(
                    UNCLASSIFIED, True, "unclassified accepted (allow_unclassified=True)"
                )
            return Classification(
                UNCLASSIFIED,
                False,
                "no production classification on the window (ax.environment is unset); "
                "failing closed (C50.1) -- the window is stored and counted but is "
                "evidence for nothing. Set ax.environment=production, or pass "
                "--allow-environments with the label this JVM does report.",
            )
        normalised = env.strip().lower()
        if normalised in self._allowed:
            return Classification(normalised, True, "explicitly production-classified")
        return Classification(
            normalised,
            False,
            f"environment {normalised!r} is not in the production allowlist "
            f"{sorted(self._allowed)} (C50.1) -- the window is stored and counted but is "
            f"evidence for nothing. Pass --allow-environments {normalised} if this really "
            "is a production environment.",
        )


def classify_payload(payload: Mapping[str, Any]) -> str | None:
    """Extract the environment label from a CONTRACTS 2 body, or None."""
    for path in _ENV_KEYS:
        node: Any = payload
        for key in path:
            if not isinstance(node, Mapping) or key not in node:
                node = None
                break
            node = node[key]
        if isinstance(node, str) and node.strip():
            return node
    return None
