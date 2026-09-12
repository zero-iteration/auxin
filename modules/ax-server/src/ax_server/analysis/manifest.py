"""CONTRACTS 1 manifest, plus the additive fields C50/C51/C52 require.

Everything added after the freeze is OPTIONAL on the wire and defaults to the
SAFE direction, which for this system always means "we cannot conclude death":

    dynamicallyObservable  absent -> NOT observable  (C51)
    shortCircuitable       absent -> IS short-circuitable
    isTest                 absent -> not a test
    callEdges[].semantics  absent -> blocking        (C52)

The first two defaults matter: a manifest that conforms to CONTRACTS 1 as
literally frozen -- with neither field -- will yield NO dead candidates at all.
That is intentional. ax-static must emit both flags before this engine can
nominate anything, and a manifest that does not is telling us it has not been
upgraded, which is exactly when a nomination would be least trustworthy.
"""

import json
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from ax_server.analysis.models import EligibilityClass, MethodRef

__all__ = [
    "CallEdge",
    "ClassEntry",
    "EntryPoint",
    "Manifest",
    "ManifestError",
    "MethodEntry",
    "TEST_ENTRY_POINT_KINDS",
    "load_manifest",
]

#: Entry-point kinds that are test harness roots, not production roots (C50.2).
TEST_ENTRY_POINT_KINDS: frozenset[str] = frozenset(
    {"test", "Test", "JUnitTest", "JUnit5Test", "TestNGTest", "SpockTest", "CucumberTest"}
)

_RESOLUTIONS = frozenset({"exact", "cha", "unresolved"})
_SEMANTICS = frozenset({"blocking", "noop", "no-op", "transitive"})
"""Edge semantics this reader accepts.

BUG #22: ax-static emits `noop` (and CONTRACTS S1 specifies `blocking | noop`) while this set
held only `no-op`/`blocking`/`transitive`, so the real server refused to LOAD the real manifest
with a ManifestError at startup -- found within 30 seconds of first connecting the two. `no-op`
and `transitive` are kept as tolerated aliases (transitive is SCARF's third class, C52) so a
producer on either spelling is never a hard startup failure; the canonical value is `noop`.
"""


class ManifestError(ValueError):
    """The manifest is unusable. We never fall back to a guess."""


@dataclass(frozen=True, slots=True)
class MethodEntry:
    idx: int
    name: str
    desc: str
    line: int | None = None
    access: str = ""
    synthetic: bool = False
    tier2: bool = False
    eligibility: EligibilityClass = EligibilityClass.NOT_DYNAMICALLY_OBSERVABLE
    #: A Spring proxy, cache hit, circuit-breaker fallback, retry/recover path
    #: or early-returning filter can complete the FEATURE without the target
    #: body ever running. "Never invoked" then says nothing about liveness.
    short_circuitable: bool = True
    is_test: bool = False

    @property
    def is_clinit(self) -> bool:
        return self.name == "<clinit>"

    @property
    def is_public(self) -> bool:
        return "public" in self.access.split()


@dataclass(frozen=True, slots=True)
class ClassEntry:
    name: str
    source_file: str = ""
    probe_count: int = 0
    schema_hash: str = ""
    is_public_api: bool = False
    is_test: bool = False
    methods: tuple[MethodEntry, ...] = ()

    @property
    def package(self) -> str:
        return self.name.rpartition(".")[0]


@dataclass(frozen=True, slots=True)
class EntryPoint:
    cls: str
    method: str
    desc: str
    kind: str

    @property
    def ref(self) -> MethodRef:
        return MethodRef(self.cls, self.method, self.desc)

    @property
    def is_test(self) -> bool:
        return self.kind in TEST_ENTRY_POINT_KINDS


@dataclass(frozen=True, slots=True)
class CallEdge:
    src: MethodRef
    dst: MethodRef
    #: CONTRACTS 1: "`unresolved` is a first-class value, not an omission."
    resolution: str = "unresolved"
    #: C52: SCARF classifies edges blocking / no-op. `if (x instanceof Bar)` is
    #: a no-op edge -- the site can be constant-folded, so it does not keep the
    #: target alive. We still refuse to call such a target dead; a no-op-only
    #: target lands in `static_reachable = None`.
    semantics: str = "blocking"

    @property
    def resolved(self) -> bool:
        return self.resolution in ("exact", "cha")


@dataclass(frozen=True, slots=True)
class Manifest:
    schema_version: int = 1
    build_sha: str = ""
    artifact: str = ""
    generated_at: str = ""
    classes: tuple[ClassEntry, ...] = ()
    entry_points: tuple[EntryPoint, ...] = ()
    call_edges: tuple[CallEdge, ...] = ()
    #: C50.2 -- library<->test strongly connected components emitted by
    #: ax-static. A library reachable only from its own test must not keep
    #: itself alive, so intra-SCC edges confer no reachability.
    library_test_sccs: tuple[frozenset[MethodRef], ...] = ()
    _by_name: dict[str, ClassEntry] = field(default_factory=dict, repr=False, compare=False)

    def __post_init__(self) -> None:
        self._by_name.update({c.name: c for c in self.classes})

    def klass(self, name: str) -> ClassEntry | None:
        return self._by_name.get(name)

    def method(self, ref: MethodRef) -> MethodEntry | None:
        entry = self._by_name.get(ref.cls)
        if entry is None:
            return None
        for m in entry.methods:
            if m.name == ref.name and m.desc == ref.desc:
                return m
        return None

    def iter_methods(self):
        for c in self.classes:
            for m in c.methods:
                yield c, m

    @property
    def test_classes(self) -> frozenset[str]:
        return frozenset(c.name for c in self.classes if c.is_test)


def _bool(value: Any, default: bool) -> bool:
    return default if value is None else bool(value)


def _eligibility(raw: Mapping[str, Any]) -> EligibilityClass:
    """Resolve the C51 eligibility class. ABSENT => NOT observable."""
    explicit = raw.get("observability")
    if isinstance(explicit, str):
        normalised = explicit.strip().lower().replace("_", "-")
        if normalised in ("observable", "dynamically-observable"):
            return EligibilityClass.OBSERVABLE
        if normalised in ("de-instrumented", "deinstrumented", "stripped"):
            return EligibilityClass.DE_INSTRUMENTED
        return EligibilityClass.NOT_DYNAMICALLY_OBSERVABLE
    flag = raw.get("dynamicallyObservable")
    if flag is None:
        return EligibilityClass.NOT_DYNAMICALLY_OBSERVABLE
    return EligibilityClass.OBSERVABLE if bool(flag) else EligibilityClass.NOT_DYNAMICALLY_OBSERVABLE


def _method(raw: Mapping[str, Any]) -> MethodEntry:
    if "idx" not in raw:
        raise ManifestError("method entry is missing `idx` (build-time probe index, C7)")
    return MethodEntry(
        idx=int(raw["idx"]),
        name=str(raw.get("name", "")),
        desc=str(raw.get("desc", "")),
        line=int(raw["line"]) if raw.get("line") is not None else None,
        access=str(raw.get("access", "")),
        synthetic=_bool(raw.get("synthetic"), False),
        tier2=_bool(raw.get("tier2"), False),
        eligibility=_eligibility(raw),
        short_circuitable=_bool(raw.get("shortCircuitable"), True),
        is_test=_bool(raw.get("isTest"), False),
    )


def _class(raw: Mapping[str, Any]) -> ClassEntry:
    name = raw.get("name")
    if not isinstance(name, str) or not name:
        raise ManifestError("class entry is missing `name`")
    methods_raw = raw.get("methods") or []
    if not isinstance(methods_raw, Sequence):
        raise ManifestError(f"{name}: `methods` must be an array")
    return ClassEntry(
        name=name,
        source_file=str(raw.get("sourceFile", "")),
        probe_count=int(raw.get("probeCount", 0) or 0),
        schema_hash=str(raw.get("schemaHash", "")),
        is_public_api=_bool(raw.get("isPublicApi"), False),
        is_test=_bool(raw.get("isTest"), False),
        methods=tuple(_method(m) for m in methods_raw),
    )


def _edge(raw: Mapping[str, Any]) -> CallEdge:
    resolution = str(raw.get("resolution", "unresolved"))
    if resolution not in _RESOLUTIONS:
        raise ManifestError(
            f"callEdges[].resolution {resolution!r} not in {sorted(_RESOLUTIONS)}; "
            "CONTRACTS 1 requires it to be honest"
        )
    semantics = str(raw.get("semantics", "blocking"))
    if semantics not in _SEMANTICS:
        raise ManifestError(f"callEdges[].semantics {semantics!r} not in {sorted(_SEMANTICS)}")
    return CallEdge(
        src=MethodRef.parse(str(raw["from"])),
        dst=MethodRef.parse(str(raw["to"])),
        resolution=resolution,
        semantics=semantics,
    )


def _sccs(raw: Any) -> tuple[frozenset[MethodRef], ...]:
    if not raw:
        return ()
    out: list[frozenset[MethodRef]] = []
    for group in raw:
        if isinstance(group, Mapping):
            members = group.get("members") or ()
        else:
            members = group
        refs = frozenset(MethodRef.parse(str(m)) for m in members)
        if refs:
            out.append(refs)
    return tuple(out)


def load_manifest(source: str | Path | Mapping[str, Any]) -> Manifest:
    """Load `auxin-manifest.json` (or an already-parsed mapping)."""
    if isinstance(source, (str, Path)):
        path = Path(source)
        try:
            raw = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise ManifestError(f"cannot read manifest {path}: {exc}") from exc
    else:
        raw = source
    if not isinstance(raw, Mapping):
        raise ManifestError("manifest root must be an object")

    classes_raw = raw.get("classes") or []
    entry_raw = raw.get("entryPoints") or []
    edges_raw = raw.get("callEdges") or []
    return Manifest(
        schema_version=int(raw.get("schemaVersion", 1)),
        build_sha=str(raw.get("buildSha", "")),
        artifact=str(raw.get("artifact", "")),
        generated_at=str(raw.get("generatedAt", "")),
        classes=tuple(_class(c) for c in classes_raw),
        entry_points=tuple(
            EntryPoint(
                cls=str(e.get("class", "")),
                method=str(e.get("method", "")),
                desc=str(e.get("desc", "")),
                kind=str(e.get("kind", "unknown")),
            )
            for e in entry_raw
        ),
        call_edges=tuple(_edge(e) for e in edges_raw),
        library_test_sccs=_sccs(raw.get("libraryTestSccs") or raw.get("testSccs")),
    )
