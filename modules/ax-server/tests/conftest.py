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
    if environment is not None:
        body["jvmClassification"] = {"env": environment}
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
def ingested(collector: CollectorService) -> CollectorService:
    """Four contiguous 24h production windows, days 0..3."""
    for day in range(4):
        collector.ingest(realistic_payload(day))
    return collector


def verdict_map(run) -> dict[str, Any]:
    return {f"{v.cls}#{v.method}{v.desc}": v for v in run.verdicts}
