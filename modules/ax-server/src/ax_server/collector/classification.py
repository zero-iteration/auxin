"""C50.1 -- JVM production classification, FAIL CLOSED.

    "The agent must refuse to report from any JVM not explicitly
    production-classified. Explicit allowlist, fail-closed (absent
    classification => do not report)."  -- VALIDATION.md C50

The agent enforces this at its end. The collector enforces it again here,
because a server that trusts its clients to police themselves does not have a
safety property, it has a convention. An unclassified window is REJECTED: not
recorded as liveness evidence, not merged, counted loudly.

CONTRACTS 2 does not define a classification field (it predates C50). We accept
several spellings additively -- the frozen fields are untouched -- and treat a
body with none of them as unclassified.
"""

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

__all__ = [
    "DEFAULT_PRODUCTION_ENVIRONMENTS",
    "Classification",
    "EnvironmentPolicy",
    "classify_payload",
]

#: The ONLY environment labels accepted as production. Everything else --
#: including the empty string, "unclassified", "staging", "ci" -- is refused.
DEFAULT_PRODUCTION_ENVIRONMENTS: tuple[str, ...] = ("production", "prod")

#: Where we look for the label, in priority order.
_ENV_KEYS: tuple[tuple[str, ...], ...] = (
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
    ) -> None:
        self._allowed = {e.strip().lower() for e in production_environments}
        # Escape hatch for local development ONLY. Turning this on in a real
        # deployment reintroduces C50: CI JVMs would count as production.
        self._allow_unclassified = allow_unclassified

    @property
    def allowed(self) -> frozenset[str]:
        return frozenset(self._allowed)

    @property
    def allow_unclassified(self) -> bool:
        return self._allow_unclassified

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
                "no production classification on the window; failing closed (C50.1)",
            )
        normalised = env.strip().lower()
        if normalised in self._allowed:
            return Classification(normalised, True, "explicitly production-classified")
        return Classification(
            normalised,
            False,
            f"environment {normalised!r} is not in the production allowlist "
            f"{sorted(self._allowed)} (C50.1)",
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
