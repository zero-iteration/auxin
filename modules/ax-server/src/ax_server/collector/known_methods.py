"""The set of `(class, idx)` pairs a build's manifest actually declares.

Why this lives in `collector/` and not in `analysis/`
-----------------------------------------------------
The collector must refuse an `edges[]` entry that names a method the manifest
does not contain -- a dangling edge is worse than a missing one, because a
dangling edge points at an identity that will never resolve and will be
silently dropped by every reader downstream. But `collector` sits BELOW
`analysis` in the one-way dependency order, so it cannot import
`analysis.manifest`.

So the collector declares the narrow port it needs -- "is `(class, idx)` a real
method in this build?" -- and `analysis` supplies the adapter over its own
`Manifest`. The dependency arrow keeps pointing the right way and the check
still runs against the real manifest.
"""

from abc import ABC, abstractmethod
from collections.abc import Iterable

__all__ = ["KnownMethods", "MethodIndex", "UNCHECKED", "UncheckedMethods"]


class KnownMethods(ABC):
    """Port: the manifest identity space the collector validates against."""

    @abstractmethod
    def knows_build(self, build_sha: str) -> bool:
        """True when this index can answer questions about `build_sha`.

        False means "no opinion". A collector that has no manifest for the
        reporting build must NOT reject its edges -- it has not found a
        dangling reference, it has merely failed to look.
        """

    @abstractmethod
    def has_method(self, build_sha: str, cls: str, idx: int) -> bool:
        """True when the manifest for `build_sha` declares `(cls, idx)`."""

    @abstractmethod
    def describe(self) -> str:
        """Human-readable provenance, for the rejection message."""


class MethodIndex(KnownMethods):
    """A frozen `(class, idx)` set for one build.

    `idx` is build-time assigned by sorting `(className, methodName,
    descriptor)` (CONTRACTS 1), so membership here is exactly the identity the
    wire uses -- no name or descriptor parsing, and no runtime class hash.
    """

    __slots__ = ("_build_sha", "_pairs", "_source")

    def __init__(
        self,
        build_sha: str,
        pairs: Iterable[tuple[str, int]],
        *,
        source: str = "manifest",
    ) -> None:
        self._build_sha = build_sha
        self._pairs = frozenset((str(c), int(i)) for c, i in pairs)
        self._source = source

    @property
    def build_sha(self) -> str:
        return self._build_sha

    def __len__(self) -> int:
        return len(self._pairs)

    def knows_build(self, build_sha: str) -> bool:
        return build_sha == self._build_sha

    def has_method(self, build_sha: str, cls: str, idx: int) -> bool:
        if build_sha != self._build_sha:
            return False
        return (cls, idx) in self._pairs

    def describe(self) -> str:
        return f"{self._source} for build {self._build_sha} ({len(self._pairs)} methods)"


class UncheckedMethods(KnownMethods):
    """The default: no manifest, so no dangling-edge check is possible.

    This is NOT "everything is valid" -- it is "we cannot tell", and the
    collector counts every window it could not verify so the gap is visible
    instead of assumed away.
    """

    def knows_build(self, build_sha: str) -> bool:
        return False

    def has_method(self, build_sha: str, cls: str, idx: int) -> bool:
        return False

    def describe(self) -> str:
        return "no manifest supplied; edge endpoints were not verified"


#: Shared singleton for the unverified case.
UNCHECKED = UncheckedMethods()
