"""C50 -- the test-liveness trap. All three defences.

If any of these regress, the product can only ever propose deleting UNTESTED
code, which is the exact inversion of what a customer wants.
"""

import pytest

from ax_server.collector.classification import EnvironmentPolicy
from ax_server.collector.errors import IngestRejected
from ax_server.collector.health import RejectReason
from ax_server.collector.service import CollectorService
from ax_server.collector.testrunner import TestRunnerDetector as RunnerDetector

from conftest import BUILD_SHA, realistic_payload

CLS = "com.acme.shipping.RateSelector"


# -- part 1: fail-closed production classification ----------------------

def test_unclassified_jvm_is_rejected(collector):
    body = realistic_payload(0)
    del body["jvmClassification"]
    with pytest.raises(IngestRejected) as exc:
        collector.ingest(body)
    assert exc.value.reason == RejectReason.NOT_PRODUCTION
    assert exc.value.status == 403
    assert collector.store.coverage(BUILD_SHA, CLS) is None


@pytest.mark.parametrize("env", ["staging", "ci", "qa", "canary", "dev", ""])
def test_non_production_environments_are_rejected(collector, env):
    body = realistic_payload(0)
    body["jvmClassification"] = {"env": env}
    with pytest.raises(IngestRejected) as exc:
        collector.ingest(body)
    assert exc.value.reason == RejectReason.NOT_PRODUCTION


def test_production_is_accepted(collector):
    assert collector.ingest(realistic_payload(0)).accepted


def test_allow_unclassified_is_an_explicit_opt_in(store):
    lenient = CollectorService(store, policy=EnvironmentPolicy(allow_unclassified=True))
    body = realistic_payload(0)
    del body["jvmClassification"]
    assert lenient.ingest(body).accepted
    assert EnvironmentPolicy().allow_unclassified is False


# -- part 2: test-runner frames -----------------------------------------

@pytest.mark.parametrize(
    "runner",
    [
        "org.junit.runners.ParentRunner",
        "org.testng.TestNG",
        "org.apache.maven.surefire.booter.ForkedBooter",
        "worker.org.gradle.process.internal.worker.GradleWorkerMain",
        "org.gradle.api.internal.tasks.testing.SuiteTestClassProcessor",
        "com.intellij.rt.junit.JUnitStarter",
        "org.spockframework.runtime.Sputnik",
        "io.cucumber.core.runtime.Runtime",
    ],
)
def test_test_runner_in_classes_loaded_taints_the_whole_window(collector, runner):
    body = realistic_payload(0)
    body["classesLoaded"].append(runner)
    with pytest.raises(IngestRejected) as exc:
        collector.ingest(body)
    assert exc.value.reason == RejectReason.TEST_RUNNER_WINDOW
    assert collector.store.coverage(BUILD_SHA, CLS) is None


def test_coverage_record_with_a_test_frame_is_discarded_and_counted(collector):
    body = realistic_payload(0)
    body["coverage"][0]["frames"] = ["org.junit.jupiter.engine.execution.MethodInvocation"]
    result = collector.ingest(body)
    assert result.accepted
    assert result.discarded_test_records == 1
    # The tainted class contributed nothing...
    assert collector.store.coverage(BUILD_SHA, CLS) is None
    # ...while the untainted records in the same window still merged.
    assert collector.store.coverage(BUILD_SHA, "com.acme.api.PublicGateway") is not None
    snap = collector.health.snapshot()
    assert snap["coverageRecordsDiscardedTest"] == 1
    assert snap["rejectsByReason"][RejectReason.TEST_RUNNER_RECORD] == 1


def test_a_probe_reported_for_a_runner_class_itself_is_discarded(collector):
    body = realistic_payload(0)
    body["coverage"].append(
        {"class": "org.junit.runners.model.FrameworkMethod", "schemaHash": "x", "probes": "AQ=="}
    )
    result = collector.ingest(body)
    assert result.discarded_test_records == 1


def test_detector_catalogue_is_configurable(collector):
    detector = RunnerDetector(prefixes=("com.acme.ourownrunner.",))
    assert detector.matches("com.acme.ourownrunner.Main") == "com.acme.ourownrunner."
    assert detector.matches("org.junit.Foo") is None


# -- part 3: library<->test SCC -----------------------------------------

def test_library_reachable_only_from_its_own_test_is_not_kept_alive(manifest):
    from ax_server.analysis.models import MethodRef
    from ax_server.analysis.reachability import compute_reachability

    r = compute_reachability(manifest)
    lib = MethodRef("com.acme.lib.LibOnlyUsedByTest", "helper", "()V")
    # Not reachable -- the test edge does not confer liveness (C50.2)...
    assert lib not in r.result.reachable
    # ...and equally, we do not claim it is provably unreachable.
    assert r.classify(lib) is None
    assert r.result.dropped_test_roots == 1
    assert r.result.dropped_test_edges >= 1
