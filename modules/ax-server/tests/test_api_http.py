"""Read-only query API, over the service and over HTTP."""

import gzip
import json
import threading
import urllib.error
import urllib.request

import pytest

from ax_server.api.http import make_api_router
from ax_server.api.service import QueryService
from ax_server.collector.http import Router, make_collector_router, serve

from conftest import BUILD_SHA, realistic_payload

RS = "com.acme.shipping.RateSelector"


@pytest.fixture
def api(ingested, engine):
    return QueryService(engine, collector=ingested)


def test_verdicts_by_class(api):
    rows = api.verdicts_by_class(BUILD_SHA, RS)
    assert {r["method"] for r in rows} == {"pick", "normalise", "legacyFallback", "cachedLookup"}
    assert all("reasons" in r for r in rows)


def test_verdicts_by_package(api):
    rows = api.verdicts_by_package(BUILD_SHA, "com.acme.shipping")
    assert {r["class"] for r in rows} == {RS, "com.acme.shipping.NeverLoaded"}


def test_verdicts_by_file(api):
    rows = api.verdicts_by_file(BUILD_SHA, "RateSelector.java")
    assert {r["class"] for r in rows} == {RS}


def test_dead_candidates_carry_the_posture(api):
    payload = api.dead_candidates(BUILD_SHA)
    assert payload["count"] == 1
    assert payload["candidates"][0]["method"] == "legacyFallback"
    assert "false-negative-biased" in payload["posture"]
    assert "72% precision" in payload["posture"]
    assert payload["phasesMissing"] == []


def test_hot_methods_computes_percentiles_server_side(api):
    rows = api.hot_methods(BUILD_SHA, since_days=3650)
    assert rows[0]["class"] == RS
    assert rows[0]["calls"] == 1201 * 4
    assert rows[0]["method"] == "pick(Ljava/util/List;)Lcom/acme/Rate;"
    assert rows[0]["percentiles"]["p50"] == 3
    assert rows[0]["bucketScheme"] == "loglinear-16-v1"


def test_agent_health_aggregates_windows_and_ingest(api):
    health = api.agent_health(BUILD_SHA)
    assert health["windows"] == 4
    assert health["degradedWindows"] == 0
    assert health["classesSkippedTotal"] == {"noManifestEntry": 48}
    assert health["instances"] == ["pod-7f3a"]
    assert health["ingest"]["windowsAccepted"] == 4


def test_coverage_windows_render_phases(api):
    payload = api.coverage_windows(BUILD_SHA)
    assert payload["windowDays"] == 4
    assert payload["phasesCovered"] == ["month-end", "peak-season"]
    assert len(payload["usable"]) == 4
    assert payload["excluded"] == []
    assert payload["usable"][0]["usableAsDeathEvidence"] is True


def test_effective_false_positive_surface(api):
    payload = api.effective_false_positives()
    assert payload["thresholds"] == {"probation": 0.10, "autoDisable": 0.25}
    assert "chooses not to take action" in payload["definition"]


def test_builds(api):
    assert api.builds() == [BUILD_SHA]


# -- HTTP ---------------------------------------------------------------


@pytest.fixture
def server(store, collector, engine):
    api = QueryService(engine, collector=collector)
    router = Router().extend(make_collector_router(collector)).extend(make_api_router(api))
    httpd = serve(router, port=0)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()
    httpd.server_close()
    thread.join(timeout=5)


def _get(base, path):
    with urllib.request.urlopen(base + path, timeout=10) as resp:
        return resp.status, json.loads(resp.read())


def test_http_ingest_then_query(server):
    body = gzip.compress(json.dumps(realistic_payload(0)).encode())
    req = urllib.request.Request(
        server + "/v1/ingest",
        data=body,
        headers={"Content-Type": "application/json", "Content-Encoding": "gzip"},
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        assert resp.status == 202
        assert json.loads(resp.read())["classesMerged"] == 6

    status, payload = _get(server, f"/v1/builds/{BUILD_SHA}/verdicts?class={RS}")
    assert status == 200
    assert len(payload) == 4

    status, health = _get(server, "/v1/ingest/health")
    assert health["windowsAccepted"] == 1

    status, windows = _get(server, f"/v1/builds/{BUILD_SHA}/coverage-windows")
    assert len(windows["usable"]) == 1


def test_http_rejects_an_unclassified_jvm_with_403(server):
    body = realistic_payload(0)
    del body["jvmClassification"]
    req = urllib.request.Request(
        server + "/v1/ingest",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    with pytest.raises(urllib.error.HTTPError) as exc:
        urllib.request.urlopen(req, timeout=10)
    assert exc.value.code == 403
    assert json.loads(exc.value.read())["error"] == "not_production_classified"


def test_http_instrumentation_gaps_route(server):
    """Bug #18 over HTTP: ingest a post-#18 window, then ask what could have
    been observed at all."""
    from conftest import realistic_payload_installed

    req = urllib.request.Request(
        server + "/v1/ingest",
        data=json.dumps(realistic_payload_installed(0)).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        assert json.loads(resp.read())["installMasksMerged"] == 6

    status, gaps = _get(server, f"/v1/builds/{BUILD_SHA}/instrumentation-gaps")
    assert status == 200
    assert gaps["maskReported"] is True
    assert gaps["observableButNeverInstrumented"] == 0
    assert gaps["installedIndices"] == 9


def test_http_unknown_route_is_404(server):
    with pytest.raises(urllib.error.HTTPError) as exc:
        urllib.request.urlopen(server + "/nope", timeout=10)
    assert exc.value.code == 404
