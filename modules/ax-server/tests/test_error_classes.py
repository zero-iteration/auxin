"""BUG #24 -- exception CLASS NAMES: decode, store, query.

    "`Tier2Aggregator.MethodStats` keeps `errorCounts[]` by class id and
    `ErrorIds` maintains the name table (1-254, 255 = overflow) -- but
    `Batch.java` wrote only the scalar `errors`, and `decode.py` read only
    that. `grep -rn errorClass modules/ax-server/src/` returned zero hits. So
    the README's "what does it throw -- exception class names" was never
    delivered: you got a count, not a type."

This file implements the READER half against CONTRACTS 2 v4 -- the spec, not
the agent. Every rule in that block gets a test:

  * ids are resolved WITHIN the window that carried them, and only NAMES are
    stored;
  * `sum(errorsByClass) < errors` is LEGAL and the remainder is reported as
    unattributed, never reconciled and never given an invented class;
  * `errorsByClass` absent or empty with `errors > 0` is LEGAL and reports
    "types unavailable", never zero types;
  * keys are JSON strings, parsed to int, and a non-numeric key is refused.
"""

import json
import urllib.error
import urllib.request
from datetime import UTC, datetime, timedelta

import pytest

from ax_server.api.service import QueryService
from ax_server.collector.errors import IngestRejected
from ax_server.collector.health import RejectReason
from ax_server.store.models import (
    ERROR_TYPES_LEGACY,
    ERROR_TYPES_UNAVAILABLE,
    ERRORS_BY_CLASS,
    Tier2Record,
)
from ax_server.store.port import Tier2ErrorStore

from conftest import BUILD_SHA, ERROR_CLASSES, payload_v4, realistic_payload

RS = "com.acme.shipping.RateSelector"
SINCE = datetime(2020, 1, 1, tzinfo=UTC)
TIMEOUT = "java.net.SocketTimeoutException"
NPE = "java.lang.NullPointerException"


def _window(collector, body):
    result = collector.ingest(body)
    assert result.accepted
    return result


def _stored(store, cls=RS, idx=0):
    return store.tier2_error_classes(BUILD_SHA, cls, idx, SINCE)


# ======================================================================
# 1. ids resolved per window, NAMES stored
# ======================================================================


def test_ids_are_resolved_to_names(collector):
    _window(collector, payload_v4(0, errors_by_class={"1": 2, "2": 1}))
    stored = _stored(collector.store)
    assert stored["byClass"] == {TIMEOUT: 2, NPE: 1}
    assert stored["attributed"] == 3
    assert stored["errors"] == 3
    assert stored["unattributed"] == 0
    assert stored["source"] == ERRORS_BY_CLASS


def test_no_id_is_ever_stored(collector):
    """The store must not contain the wire ids anywhere.

    `errorClasses` is window-local, so a stored id is a number whose meaning
    expired at the next flush. The guard is deliberately crude -- read the raw
    column -- because a subtler check would pass on the exact bug it exists to
    catch.
    """
    _window(collector, payload_v4(0, errors_by_class={"1": 2, "2": 1}))
    raw = collector.store._conn.execute(
        "SELECT error_types FROM tier2 WHERE build_sha=?", (BUILD_SHA,)
    ).fetchone()["error_types"]
    parsed = json.loads(raw)
    assert set(parsed) == {TIMEOUT, NPE}
    assert not any(key.isdigit() for key in parsed)


def test_the_same_id_in_two_windows_means_two_different_classes(collector):
    """THE rule. "id 1 in one window is unrelated to id 1 in the next."

    Window 0 says id 1 is a SocketTimeoutException; window 1 says id 1 is an
    IllegalStateException. A reader that kept the first table, or resolved
    lazily at query time, would report four timeouts. The correct answer is
    two of each.
    """
    _window(collector, payload_v4(0, error_classes={"1": TIMEOUT}, errors_by_class={"1": 2},
                                  errors=2))
    _window(
        collector,
        payload_v4(
            1,
            error_classes={"1": "java.lang.IllegalStateException"},
            errors_by_class={"1": 2},
            errors=2,
        ),
    )
    stored = _stored(collector.store)
    assert stored["byClass"] == {TIMEOUT: 2, "java.lang.IllegalStateException": 2}
    assert stored["errors"] == 4
    assert stored["unattributed"] == 0


def test_the_overflow_bucket_is_a_named_class_like_any_other(collector):
    """Id 255 has a NAME in the table (`<overflow>`), so it is attributed.

    It is not the same thing as an unattributed error: the agent is telling us
    "this happened, and it fell outside my 254-entry table", which is a fact
    worth keeping under the name it gave.
    """
    _window(collector, payload_v4(0, errors_by_class={"1": 1, "255": 2}))
    stored = _stored(collector.store)
    assert stored["byClass"] == {"<overflow>": 2, TIMEOUT: 1}
    assert stored["unattributed"] == 0


def test_a_window_with_no_table_resolves_nothing_and_invents_nothing(collector):
    """`errorsByClass` with no `errorClasses` cannot be resolved.

    The counts are REAL -- three exceptions happened -- so they become
    unattributed rather than disappearing, and they are NOT given a name like
    "class id 1".
    """
    _window(collector, payload_v4(0, error_classes=None, errors_by_class={"1": 3}))
    stored = _stored(collector.store)
    assert stored["byClass"] == {}
    assert stored["errors"] == 3
    assert stored["unattributed"] == 3
    assert stored["unresolvedIds"] == 3


# ======================================================================
# 2. sum < errors is LEGAL -> unattributed
# ======================================================================


def test_a_shortfall_is_reported_as_unattributed(collector):
    """`errors` is incremented unconditionally; the table holds 254 classes.

    So 10 errors with 4 attributed is a correct window, not a broken one.
    """
    _window(collector, payload_v4(0, errors=10, errors_by_class={"1": 3, "2": 1}))
    stored = _stored(collector.store)
    assert stored["errors"] == 10
    assert stored["attributed"] == 4
    assert stored["unattributed"] == 6
    assert stored["byClass"] == {TIMEOUT: 3, NPE: 1}


def test_the_shortfall_is_never_reconciled_into_a_class(collector):
    _window(collector, payload_v4(0, errors=10, errors_by_class={"1": 3, "2": 1}))
    stored = _stored(collector.store)
    # Exactly two names, and the six unattributed errors are nowhere in them.
    assert sum(stored["byClass"].values()) == 4
    assert len(stored["byClass"]) == 2
    assert "unattributed" not in stored["byClass"]
    assert "unknown" not in stored["byClass"]


def test_the_record_model_clamps_a_broken_producer(collector):
    """A sum LARGER than `errors` is a broken agent, not a negative remainder.

    We keep the names (they are still what was thrown) and refuse to publish
    a nonsense "-2 unattributed".
    """
    record = Tier2Record(RS, 0, 100, 1, {TIMEOUT: 3}, (0,), "loglinear-16-v1")
    assert record.attributed_errors == 3
    assert record.unattributed_errors == 0


def test_the_shortfall_is_counted_in_ingest_health(collector):
    _window(collector, payload_v4(0, errors=10, errors_by_class={"1": 3, "2": 1}))
    attribution = collector.health.snapshot()["exceptionAttribution"]
    assert attribution["errorsAttributed"] == 4
    assert attribution["errorsUnattributed"] == 6
    assert attribution["tier2RecordsWithErrorTypes"] == 1
    assert "never reconciled" in attribution["note"]


def test_the_result_reports_the_split(collector):
    result = _window(collector, payload_v4(0, errors=10, errors_by_class={"1": 3, "2": 1}))
    assert result.errors_attributed == 4
    assert result.errors_unattributed == 6
    assert result.error_classes_named == 3  # ids 1, 2, 255 in the table
    body = result.to_json()
    assert body["errorsUnattributed"] == 6
    assert "overflow" in body["errorAttributionNote"]


# ======================================================================
# 3. absent / empty errorsByClass with errors > 0 -> "types unavailable"
# ======================================================================


def test_absent_errors_by_class_reports_types_unavailable(collector):
    body = payload_v4(0, errors=7, errors_by_class=None)
    assert "errorsByClass" not in body["tier2"][0]
    assert "errorTypes" not in body["tier2"][0]
    _window(collector, body)

    stored = _stored(collector.store)
    assert stored["errors"] == 7, "the COUNT survives"
    assert stored["byClass"] == {}
    assert stored["typesAvailable"] is False
    assert stored["source"] == ERROR_TYPES_UNAVAILABLE
    # Not zero types: seven errors of unknown type.
    assert stored["unattributed"] == 7


def test_an_empty_errors_by_class_is_the_same_legal_case(collector):
    _window(collector, payload_v4(0, errors=7, errors_by_class={}))
    stored = _stored(collector.store)
    assert stored["errors"] == 7
    assert stored["typesAvailable"] is False
    assert stored["unattributed"] == 7


def test_the_phrase_types_unavailable_actually_reaches_the_reader(collector, engine):
    _window(collector, payload_v4(0, errors=7, errors_by_class=None))
    api = QueryService(engine, collector=collector)
    row = api.hot_methods(BUILD_SHA, since_days=3650)[0]
    assert row["errors"] == 7
    assert row["errorTypesAvailable"] is False
    assert "TYPES ARE UNAVAILABLE" in row["errorReading"]
    assert "not 'zero exception types'" in row["errorReading"]

    aggregate = api.exception_classes(BUILD_SHA, since_days=3650)
    assert aggregate["errors"] == 7
    assert aggregate["topClasses"] == []
    assert "TYPES ARE UNAVAILABLE" in aggregate["reading"]


def test_it_is_counted_as_a_record_without_types(collector):
    _window(collector, payload_v4(0, errors=7, errors_by_class=None))
    attribution = collector.health.snapshot()["exceptionAttribution"]
    assert attribution["tier2RecordsWithoutErrorTypes"] == 1
    assert attribution["tier2RecordsWithErrorTypes"] == 0


def test_zero_errors_and_no_types_is_not_flagged(collector):
    """`errors == 0` needs no breakdown, so it is not a missing one."""
    _window(collector, payload_v4(0, errors=0, errors_by_class=None))
    attribution = collector.health.snapshot()["exceptionAttribution"]
    assert attribution["tier2RecordsWithoutErrorTypes"] == 0


def test_the_pre_v4_errortypes_shape_is_still_read(collector):
    """A deployed agent on the old wire must not be silently stripped.

    `realistic_payload` carries `errorTypes` with names already spelled out.
    """
    body = realistic_payload(0)
    assert "errorTypes" in body["tier2"][0]
    _window(collector, body)
    stored = _stored(collector.store)
    assert stored["byClass"] == {TIMEOUT: 3}
    assert stored["source"] == ERROR_TYPES_LEGACY
    assert stored["typesAvailable"] is True


def test_errors_by_class_wins_over_the_legacy_key(collector):
    """v4 is canonical; the legacy key is not merged on top of it."""
    body = payload_v4(0, errors_by_class={"2": 3}, drop_error_types=False)
    body["tier2"][0]["errorTypes"] = {"com.acme.Stale": 99}
    _window(collector, body)
    stored = _stored(collector.store)
    assert stored["byClass"] == {NPE: 3}


# ======================================================================
# 4. keys are JSON strings, parsed to int, non-numeric REFUSED
# ======================================================================


@pytest.mark.parametrize(
    "table",
    [
        {"one": TIMEOUT},
        {"": TIMEOUT},
        {"1.5": TIMEOUT},
        {"0x1": TIMEOUT},
        {"-1": TIMEOUT},
        {"１": TIMEOUT},  # full-width digit: `isdigit()` is True, `isascii()` is not
    ],
)
def test_a_non_numeric_error_class_key_is_refused(collector, table):
    with pytest.raises(IngestRejected) as exc:
        collector.ingest(payload_v4(0, error_classes=table, errors_by_class={"1": 1}))
    assert exc.value.reason == RejectReason.BAD_ERROR_CLASS_ID


@pytest.mark.parametrize("key", ["nope", "", "1e3"])
def test_a_non_numeric_errors_by_class_key_is_refused(collector, key):
    with pytest.raises(IngestRejected) as exc:
        collector.ingest(payload_v4(0, errors_by_class={key: 1}))
    assert exc.value.reason == RejectReason.BAD_ERROR_CLASS_ID


@pytest.mark.parametrize("key", ["0", "256", "1000"])
def test_an_id_outside_the_contract_space_is_refused(collector, key):
    """CONTRACTS 2 v4 defines 1-254 plus 255 = overflow. Anything else means
    the producer is not speaking this protocol, and guessing which end of the
    range it meant is how coverage gets misattributed."""
    with pytest.raises(IngestRejected) as exc:
        collector.ingest(payload_v4(0, error_classes={key: TIMEOUT}))
    assert exc.value.reason == RejectReason.BAD_ERROR_CLASS_ID


def test_a_refused_key_is_refused_not_skipped(collector):
    """Skipping the key would silently drop the errors filed under it."""
    with pytest.raises(IngestRejected):
        collector.ingest(payload_v4(0, error_classes={"nope": TIMEOUT}))
    assert collector.store.observed_windows(BUILD_SHA) == []


def test_a_non_string_name_is_refused(collector):
    with pytest.raises(IngestRejected) as exc:
        collector.ingest(payload_v4(0, error_classes={"1": ""}))
    assert exc.value.reason == RejectReason.BAD_FIELD


def test_a_non_object_error_classes_is_refused(collector):
    body = payload_v4(0)
    body["errorClasses"] = ["java.lang.Throwable"]
    with pytest.raises(IngestRejected) as exc:
        collector.ingest(body)
    assert exc.value.reason == RejectReason.BAD_FIELD


def test_a_negative_count_is_refused(collector):
    with pytest.raises(IngestRejected) as exc:
        collector.ingest(payload_v4(0, errors_by_class={"1": -1}))
    assert exc.value.reason == RejectReason.BAD_FIELD


def test_a_v4_window_still_decodes_as_schema_version_2(collector):
    """Additive: `schemaVersion` stays 2 (CONTRACTS 2 v4, and bug #17)."""
    body = payload_v4(0, errors_by_class={"1": 3})
    assert body["schemaVersion"] in (1, 2)
    body["schemaVersion"] = 2
    assert collector.ingest(body).accepted


def test_an_agent_that_sends_neither_key_is_unaffected(collector):
    """A v2 producer: no `errorClasses`, no `errorsByClass`. Bug #17's rule."""
    body = realistic_payload(0)
    body["tier2"][0].pop("errorTypes")
    assert collector.ingest(body).accepted


# ======================================================================
# 5. surfaced: per-method and aggregate
# ======================================================================


@pytest.fixture
def api_v4(collector, engine):
    """Four days, two classes per day, plus an unattributed remainder."""
    for day in range(4):
        collector.ingest(payload_v4(day, errors=5, errors_by_class={"1": 2, "2": 1}))
    return QueryService(engine, collector=collector)


def test_hot_methods_carries_the_per_method_breakdown(api_v4):
    row = api_v4.hot_methods(BUILD_SHA, since_days=3650)[0]
    assert row["class"] == RS
    assert row["method"] == "pick(Ljava/util/List;)Lcom/acme/Rate;"
    assert row["errors"] == 20
    assert row["errorClasses"] == {TIMEOUT: 8, NPE: 4}
    assert row["errorsAttributed"] == 12
    assert row["errorsUnattributed"] == 8
    assert row["errorTypesSource"] == ERRORS_BY_CLASS
    assert "8 are UNATTRIBUTED" in row["errorReading"]
    # The percentiles that were already there are untouched.
    assert row["percentiles"]["p50"] == 3


def test_top_exception_classes_for_the_build(api_v4):
    out = api_v4.exception_classes(BUILD_SHA, since_days=3650)
    assert out["supported"] is True
    assert out["topClasses"] == [
        {"class": TIMEOUT, "errors": 8},
        {"class": NPE, "errors": 4},
    ]
    assert out["distinctClasses"] == 2
    assert out["errors"] == 20
    assert out["attributed"] == 12
    assert out["unattributed"] == 8
    # The unattributed remainder is NOT an entry in the ranking.
    assert all(row["class"] not in ("", "unattributed") for row in out["topClasses"])
    assert "overflow" in out["note"]


def test_the_aggregate_spans_methods(collector, engine):
    for day in range(2):
        body = payload_v4(day, errors=2, errors_by_class={"1": 2})
        body["tier2"].append(
            {
                "class": RS,
                "idx": 1,
                "calls": 10,
                "errors": 1,
                "errorsByClass": {"2": 1},
                "buckets": [1],
                "bucketScheme": "loglinear-16-v1",
            }
        )
        collector.ingest(body)
    out = QueryService(engine, collector=collector).exception_classes(
        BUILD_SHA, since_days=3650
    )
    assert out["byClass"] == {TIMEOUT: 4, NPE: 2}
    per_method = collector.store.tier2_error_classes(BUILD_SHA, RS, 1, SINCE)
    assert per_method["byClass"] == {NPE: 2}


def test_the_limit_caps_the_listing_but_not_the_totals(collector, engine):
    table = {str(i): f"com.acme.E{i}" for i in range(1, 21)}
    collector.ingest(
        payload_v4(
            0,
            error_classes=table,
            errors_by_class={str(i): i for i in range(1, 21)},
            errors=sum(range(1, 21)),
        )
    )
    out = QueryService(engine, collector=collector).exception_classes(
        BUILD_SHA, since_days=3650, limit=3
    )
    assert len(out["topClasses"]) == 3
    assert out["topClasses"][0] == {"class": "com.acme.E20", "errors": 20}
    assert out["distinctClasses"] == 20
    assert out["attributed"] == sum(range(1, 21))


def test_the_store_implements_the_optional_port(store):
    assert isinstance(store, Tier2ErrorStore)


def test_a_store_without_the_port_says_so(engine):
    class Blind:
        pass

    engine.store = Blind()  # type: ignore[assignment]
    out = QueryService(engine).exception_classes(BUILD_SHA)
    assert out["supported"] is False
    assert out["topClasses"] == []
    assert "Tier2ErrorStore" in out["note"]


def test_the_frozen_store_port_did_not_grow(store):
    """CONTRACTS 3's eight abstract methods stay frozen; #24 is a port
    extension, exactly as `EdgeStore` and `ProbeInstallStore` are."""
    from ax_server.store.port import Store

    assert Store.__abstractmethods__ == frozenset(
        {
            "record_window",
            "merge_coverage",
            "first_seen",
            "last_seen",
            "coverage",
            "observed_windows",
            "class_loaded_ever",
            "tier2_buckets",
        }
    )


# ======================================================================
# 6. over HTTP and MCP
# ======================================================================


def test_http_exception_classes_route(collector, engine):
    import threading

    from ax_server.api.http import make_api_router
    from ax_server.collector.http import Router, make_collector_router, serve

    router = (
        Router()
        .extend(make_collector_router(collector))
        .extend(make_api_router(QueryService(engine, collector=collector)))
    )
    httpd = serve(router, port=0)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{httpd.server_address[1]}"
    try:
        req = urllib.request.Request(
            base + "/v1/ingest",
            data=json.dumps(payload_v4(0, errors=5, errors_by_class={"1": 2, "2": 1})).encode(),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=10) as resp:
            assert json.loads(resp.read())["errorsUnattributed"] == 2
        with urllib.request.urlopen(
            base + f"/v1/builds/{BUILD_SHA}/exception-classes?sinceDays=3650", timeout=10
        ) as resp:
            out = json.loads(resp.read())
        assert out["topClasses"][0]["class"] == TIMEOUT
        assert out["unattributed"] == 2
    finally:
        httpd.shutdown()
        httpd.server_close()
        thread.join(timeout=5)


def test_mcp_exposes_the_breakdown(collector, engine):
    from ax_server.mcp.server import McpServer

    collector.ingest(payload_v4(0, errors=5, errors_by_class={"1": 2, "2": 1}))
    mcp = McpServer(QueryService(engine, collector=collector))
    resp = mcp.handle(
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "tools/call",
            "params": {
                "name": "gt_exception_classes",
                "arguments": {"buildSha": BUILD_SHA, "sinceDays": 3650},
            },
        }
    )
    out = json.loads(resp["result"]["content"][0]["text"])
    assert out["topClasses"][0]["class"] == TIMEOUT
    assert out["unattributed"] == 2
    tool = mcp.tools["gt_exception_classes"]
    assert "unattributed" in tool.description
    assert "does NOT mean zero exception types" in tool.description


def test_the_contract_v4_example_decodes_verbatim(collector):
    """CONTRACTS 2 v4's own worked example, byte for byte.

        "errorClasses": {"1": "java.net.SocketTimeoutException",
                         "2": "java.lang.NullPointerException",
                         "255": "<overflow>"}
        "tier2": [{"class": "...OrderHandler", "idx": 3, "calls": 1201,
                   "errors": 3, "errorsByClass": {"1": 2, "2": 1},
                   "buckets": [0,0,14,881,306,0]}]
    """
    body = realistic_payload(0)
    body["errorClasses"] = dict(ERROR_CLASSES)
    body["tier2"] = [
        {
            "class": RS,
            "idx": 3,
            "calls": 1201,
            "errors": 3,
            "errorsByClass": {"1": 2, "2": 1},
            "buckets": [0, 0, 14, 881, 306, 0],
        }
    ]
    assert collector.ingest(body).accepted
    stored = collector.store.tier2_error_classes(BUILD_SHA, RS, 3, SINCE)
    assert stored["byClass"] == {TIMEOUT: 2, NPE: 1}
    assert stored["errors"] == 3
    assert stored["unattributed"] == 0


def test_a_non_production_window_still_yields_its_exception_classes(collector, engine):
    """Bug #22b x #24: tier-2 telemetry is not evidence, so it is kept.

    This is the concrete thing a first-time user on an unclassified JVM gets to
    SEE: latency percentiles and exception class names, with nothing being
    claimed about liveness.
    """
    body = payload_v4(0, errors=5, errors_by_class={"1": 2, "2": 1})
    del body["jvmClassification"]
    assert collector.ingest(body).production is False
    out = QueryService(engine, collector=collector).exception_classes(
        BUILD_SHA, since_days=3650
    )
    assert out["topClasses"][0] == {"class": TIMEOUT, "errors": 2}
    assert collector.store.coverage(BUILD_SHA, RS) is None


def test_tier2_buckets_still_sum_across_windows(collector):
    """Regression guard: the added columns did not disturb the bucket sum."""
    for day in range(3):
        collector.ingest(payload_v4(day, errors_by_class={"1": 1}))
    total = collector.store.tier2_buckets(BUILD_SHA, RS, 0, SINCE)
    assert total == [0, 0, 42, 2643, 918, 0]


def test_a_pre_migration_database_gains_the_columns(tmp_path):
    """The columns postdate the tables, so an existing file must be migrated.

    `CREATE TABLE IF NOT EXISTS` is a no-op on an existing database, so
    without the migration a deployed collector would raise `no such column`
    on its next ingest -- a 100%-data-loss upgrade, which is precisely the
    bug-#17 shape.
    """
    import sqlite3

    path = str(tmp_path / "old.sqlite")
    old = sqlite3.connect(path)
    old.executescript(
        """
        CREATE TABLE windows (
            window_id INTEGER PRIMARY KEY AUTOINCREMENT, build_sha TEXT NOT NULL,
            artifact TEXT NOT NULL, instance_id TEXT NOT NULL,
            window_start_ms INTEGER NOT NULL, window_end_ms INTEGER NOT NULL,
            degraded INTEGER NOT NULL, environment TEXT NOT NULL,
            production INTEGER NOT NULL, test_tainted INTEGER NOT NULL,
            health_json TEXT NOT NULL, received_ms INTEGER NOT NULL);
        CREATE TABLE tier2 (
            window_id INTEGER NOT NULL, build_sha TEXT NOT NULL, cls TEXT NOT NULL,
            idx INTEGER NOT NULL, window_end_ms INTEGER NOT NULL, calls INTEGER NOT NULL,
            errors INTEGER NOT NULL, error_types TEXT NOT NULL, buckets TEXT NOT NULL,
            bucket_scheme TEXT NOT NULL, PRIMARY KEY (window_id, cls, idx));
        """
    )
    old.commit()
    old.close()

    from ax_server.collector.service import CollectorService
    from ax_server.store.sqlite_store import SqliteStore

    store = SqliteStore(path)
    try:
        columns = {
            row["name"] for row in store._conn.execute("PRAGMA table_info(tier2)").fetchall()
        }
        assert {"error_types_source", "unattributed_errors", "unresolved_id_errors"} <= columns
        window_columns = {
            row["name"] for row in store._conn.execute("PRAGMA table_info(windows)").fetchall()
        }
        assert "liveness_evidence" in window_columns

        collector = CollectorService(store)
        assert collector.ingest(payload_v4(0, errors_by_class={"1": 2, "2": 1})).accepted
        assert store.tier2_error_classes(BUILD_SHA, RS, 0, SINCE)["byClass"] == {
            TIMEOUT: 2,
            NPE: 1,
        }
        # And the pre-migration default preserves the old meaning: a window
        # row that predates the column is still evidence if it was production.
        assert store.observed_windows(BUILD_SHA)[0].liveness_evidence is True
    finally:
        store.close()


def test_since_filters_the_breakdown(collector):
    collector.ingest(payload_v4(0, errors_by_class={"1": 2, "2": 1}))
    future = datetime.now(tz=UTC) + timedelta(days=3650)
    assert collector.store.tier2_error_classes(BUILD_SHA, RS, 0, future)["byClass"] == {}
    assert collector.store.error_class_totals(BUILD_SHA, future)["topClasses"] == []
