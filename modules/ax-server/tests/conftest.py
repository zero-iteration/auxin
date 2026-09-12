"""Shared fixtures. The payload builder emits CONTRACTS 2 bodies verbatim."""

import base64
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest

from ax_server.analysis.engine import AnalysisConfig, AnalysisEngine
from ax_server.analysis.manifest import load_manifest
from ax_server.analysis.phases import PhaseCalendar, PhaseOccurrence
from ax_server.analysis.proposals import SqliteProposalLedger
from ax_server.analysis.runtime_edges import manifest_method_index
from ax_server.analysis.suppression import Suppressions
from ax_server.collector.service import CollectorService
from ax_server.store.bitset import from_indices
from ax_server.store.sqlite_store import SqliteStore

FIXTURES = Path(__file__).parent / "fixtures"
BUILD_SHA = "abc123def"
ARTIFACT = "checkout-service"

#: Day 0 of the synthetic observation span.
DAY0 = datetime(2026, 9, 8, 0, 0, tzinfo=UTC)


def ms(dt: datetime) -> int:
    return int(dt.timestamp() * 1000)


def probes(*indices: int) -> str:
    """base64 of a packed bitset, LSB = idx 0 (CONTRACTS 2)."""
    return base64.b64encode(from_indices(indices)).decode("ascii")


def payload(
    *,
    start: datetime,
    end: datetime,
    coverage: list[dict[str, Any]] | None = None,
    classes_loaded: list[str] | None = None,
    tier2: list[dict[str, Any]] | None = None,
    degraded: bool = False,
    environment: str | None = "production",
    instance_id: str = "pod-7f3a",
    schema_version: int = 1,
    edges: list[dict[str, Any]] | None = None,
    edge_health: dict[str, Any] | None = None,
) -> dict[str, Any]:
    body: dict[str, Any] = {
        "schemaVersion": schema_version,
        "buildSha": BUILD_SHA,
        "artifact": ARTIFACT,
        "instanceId": instance_id,
        "windowStartMs": ms(start),
        "windowEndMs": ms(end),
        "agentHealth": {
            "transformFailures": 0,
            "classesSkipped": {"noManifestEntry": 12},
            "ringDropped": 0,
            "clockNs": 26,
            "clockDegraded": False,
            "degraded": degraded,
        },
        "classesLoaded": classes_loaded if classes_loaded is not None else [],
        "coverage": coverage or [],
        "tier2": tier2 or [],
    }
    if edges is not None:
        # CONTRACTS 2 v3: the key is ALWAYS present on a v3 producer, and an
        # absent key is a different fact from an empty array -- so the builder
        # omits it entirely unless asked, which is how a v2 agent looks.
        body["edges"] = edges
        body["agentHealth"].update(DEFAULT_EDGE_HEALTH)
    if edge_health is not None:
        body["agentHealth"].update(edge_health)
    if environment is not None:
        body["jvmClassification"] = {"env": environment}
    return body


#: The v3 counters as a healthy 1-in-1024 deployment reports them.
DEFAULT_EDGE_HEALTH: dict[str, Any] = {
    "edgesEnabled": True,
    "edgesSampleRate": 1024,
    "edgesSampledRoots": 41,
    "edgesRecorded": 15,
    "edgesDropped": 0,
    "edgesTruncatedDepth": 1,
    "edgesTruncatedRoot": 0,
    "edgesTruncatedDistinct": 0,
    "edgeTierFailures": 0,
    "edgeTracesReaped": 0,
}


def installed_mask(*indices: int, width: int = 1) -> str:
    """base64 of a `coverage[].probesInstalled` mask (bug #18).

    Packed exactly like `probes` (LSB = idx 0) because it indexes the same
    build-time probe indices. `width` forces at least one byte so the
    tier-1-disabled case ships a REAL all-zero mask ("AA==") rather than an
    empty string -- an all-zero mask and an absent key are different facts and
    the tests need to exercise both.
    """
    return base64.b64encode(from_indices(indices, size_bytes=width)).decode("ascii")


#: What a healthy JVM reports having installed, per class in
#: tests/fixtures/manifest.json: every index EXCEPT the C51
#: `dynamicallyObservable: false` one (Constants#maxRetries, idx 0), which the
#: emitter skips for a reason the manifest already explains.
FULLY_INSTALLED: dict[str, list[int]] = {
    "com.acme.shipping.RateSelector": [0, 1, 2, 3],
    "com.acme.api.PublicGateway": [0],
    "com.acme.emergency.KillSwitch": [0],
    "com.acme.util.Constants": [1],
    "com.acme.billing.MonthEndReport": [0],
    "com.acme.lib.LibOnlyUsedByTest": [0],
}

#: What a JVM with `ax.tier1.enabled=false` reports: every bitset all-zero,
#: every method still `dynamicallyObservable: true`. Before bug #18 was closed
#: this was indistinguishable from "every method in your codebase is dead".
TIER1_DISABLED: dict[str, list[int]] = {cls: [] for cls in FULLY_INSTALLED}


def realistic_payload_installed(
    day: int,
    *,
    installed: dict[str, list[int]] | None = None,
    strip_mask_missing: int | None = None,
    instance_id: str = "pod-7f3a",
    probes_set: dict[str, list[int]] | None = None,
) -> dict[str, Any]:
    """`realistic_payload` plus CONTRACTS 2 `coverage[].probesInstalled`.

    `installed` maps class -> the indices THIS JVM actually installed a probe
    at; the key is always written, so every record carries a mask (that is
    what the post-#18 agent does). `probes_set` optionally overrides the
    liveness bits so a window can report "installed, and nothing ran".
    """
    body = realistic_payload(day)
    body["instanceId"] = instance_id
    masks = FULLY_INSTALLED if installed is None else installed
    for rec in body["coverage"]:
        rec["probesInstalled"] = installed_mask(*masks.get(rec["class"], ()))
        if probes_set is not None:
            rec["probes"] = probes(*probes_set.get(rec["class"], ()))
    if strip_mask_missing is not None:
        body["agentHealth"]["stripMaskMissing"] = strip_mask_missing
    return body


RS = "com.acme.shipping.RateSelector"
GATEWAY = "com.acme.api.PublicGateway"

#: `(class, idx)` on both ends -- the same manifest identity `tier2[]` uses.
#: dispatch -> pick -> normalise, plus cachedLookup -> normalise.
DEFAULT_EDGES: list[dict[str, Any]] = [
    {"fromClass": GATEWAY, "fromIdx": 0, "toClass": RS, "toIdx": 0, "count": 7},
    {"fromClass": RS, "fromIdx": 0, "toClass": RS, "toIdx": 1, "count": 5},
    {"fromClass": RS, "fromIdx": 3, "toClass": RS, "toIdx": 1, "count": 3},
]


def realistic_payload_v3(
    day: int,
    *,
    instance_id: str = "pod-7f3a",
    edges: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """`realistic_payload` plus the CONTRACTS 2 v3 `edges[]` tier."""
    body = realistic_payload(day)
    body["instanceId"] = instance_id
    # Copied, not aliased: a test that mutates one entry must not edit the
    # shared constant out from under every other test.
    source = DEFAULT_EDGES if edges is None else edges
    body["edges"] = [dict(e) for e in source]
    body["agentHealth"].update(DEFAULT_EDGE_HEALTH)
    return body


LOADED_CLASSES = [
    "com.acme.shipping.RateSelector",
    "com.acme.api.PublicGateway",
    "com.acme.emergency.KillSwitch",
    "com.acme.util.Constants",
    "com.acme.billing.MonthEndReport",
    "com.acme.lib.LibOnlyUsedByTest",
]


def realistic_payload(day: int) -> dict[str, Any]:
    """A full CONTRACTS 2 body for day `day` of the span."""
    start = DAY0 + timedelta(days=day)
    return payload(
        start=start,
        end=start + timedelta(days=1),
        classes_loaded=list(LOADED_CLASSES),
        coverage=[
            {
                "class": "com.acme.shipping.RateSelector",
                "schemaHash": "5f2a0001",
                # pick + normalise executed; legacyFallback and cachedLookup never.
                "probes": probes(0, 1),
            },
            {"class": "com.acme.api.PublicGateway", "schemaHash": "5f2a0002", "probes": probes()},
            {"class": "com.acme.emergency.KillSwitch", "schemaHash": "5f2a0003", "probes": probes()},
            {"class": "com.acme.util.Constants", "schemaHash": "5f2a0004", "probes": probes()},
            {
                "class": "com.acme.billing.MonthEndReport",
                "schemaHash": "5f2a0006",
                "probes": probes(),
            },
            {
                "class": "com.acme.lib.LibOnlyUsedByTest",
                "schemaHash": "5f2a0007",
                "probes": probes(),
            },
        ],
        tier2=[
            {
                "class": "com.acme.shipping.RateSelector",
                "idx": 0,
                "calls": 1201,
                "errors": 3,
                "errorTypes": {"java.net.SocketTimeoutException": 3},
                "buckets": [0, 0, 14, 881, 306, 0],
                "bucketScheme": "loglinear-16-v1",
            }
        ],
    )


@pytest.fixture
def store() -> SqliteStore:
    s = SqliteStore(":memory:")
    yield s
    s.close()


@pytest.fixture
def collector(store: SqliteStore) -> CollectorService:
    return CollectorService(store)


@pytest.fixture
def manifest():
    return load_manifest(FIXTURES / "manifest.json")


@pytest.fixture
def suppressions() -> Suppressions:
    return Suppressions.from_file(FIXTURES / "suppress.txt")


@pytest.fixture
def calendar() -> PhaseCalendar:
    """Phases aligned to the synthetic 4-day span so coverage is complete."""
    return PhaseCalendar(
        required=("month-end", "peak-season"),
        occurrences=(
            PhaseOccurrence("month-end", DAY0 + timedelta(days=2, hours=6),
                            DAY0 + timedelta(days=2, hours=12), "sept-2026"),
            PhaseOccurrence("peak-season", DAY0 + timedelta(days=1),
                            DAY0 + timedelta(days=1, hours=6), "peak-2026"),
        ),
        recurring=(),
        min_coverage_fraction=0.9,
    )


@pytest.fixture
def engine(store, manifest, suppressions, calendar) -> AnalysisEngine:
    e = AnalysisEngine(
        store,
        manifest,
        config=AnalysisConfig(phase_calendar=calendar),
        suppressions=suppressions,
        ledger=SqliteProposalLedger(":memory:"),
    )
    yield e
    e.close()


@pytest.fixture
def edge_collector(store: SqliteStore, manifest) -> CollectorService:
    """A collector that validates `edges[]` endpoints against the manifest.

    Separate from `collector` on purpose: the default is "no manifest, cannot
    check", and the dangling-edge tests need both halves of that.
    """
    return CollectorService(store, known_methods=manifest_method_index(manifest))


@pytest.fixture
def ingested_v3(edge_collector: CollectorService) -> CollectorService:
    """Days 0..3 from TWO pods, every window carrying `edges[]`.

    Eight windows, so edge counts must be 8x a single window's while the
    coverage bitset is unchanged -- the additive-vs-OR contrast, ingested.
    """
    for day in range(4):
        for pod in ("pod-a", "pod-b"):
            edge_collector.ingest(realistic_payload_v3(day, instance_id=pod))
    return edge_collector


@pytest.fixture
def ingested(collector: CollectorService) -> CollectorService:
    """Four contiguous 24h production windows, days 0..3."""
    for day in range(4):
        collector.ingest(realistic_payload(day))
    return collector


@pytest.fixture
def ingested_installed(collector: CollectorService) -> CollectorService:
    """Days 0..3 from a post-#18 agent: every record carries the install mask.

    The same four windows as `ingested`, so a verdict that differs between the
    two fixtures differs BECAUSE of the mask and nothing else.
    """
    for day in range(4):
        collector.ingest(realistic_payload_installed(day))
    return collector


@pytest.fixture
def ingested_tier1_disabled(collector: CollectorService) -> CollectorService:
    """Days 0..3 from a JVM with `ax.tier1.enabled=false`.

    Every coverage bitset all-zero, every install mask all-zero, every method
    still `dynamicallyObservable: true`. This is the shape that used to read
    as "every method in your codebase is dead" (bug #18).
    """
    for day in range(4):
        collector.ingest(
            realistic_payload_installed(
                day, installed=TIER1_DISABLED, probes_set={}
            )
        )
    return collector


def verdict_map(run) -> dict[str, Any]:
    return {f"{v.cls}#{v.method}{v.desc}": v for v in run.verdicts}
