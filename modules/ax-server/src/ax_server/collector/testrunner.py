"""C50.3 -- test-runner frame detection.

Google Sensenmann, verbatim (VALIDATION.md C50):

    "The testing infrastructure is going to run all those tests, including
    lib2_test, despite lib2 never being executed 'for real'. This means we
    cannot use test runs as a 'liveness' signal: if we did... We would only be
    able to clean up untested code, which would severely hamper our efforts."

That is a correctness bug, not a noise filter. If a single CI or smoke-test JVM
ever reports in, every *tested* method becomes permanently LIVE and the only
code the product can ever propose deleting is the code nobody tested -- the
exact inversion of what a customer wants.

This module is one of three defences. The other two are
`collector.classification` (fail-closed production allowlist, C50.1) and
`analysis.reachability` (library<->test SCC, C50.2).
"""

from collections.abc import Iterable, Sequence
from dataclasses import dataclass

__all__ = ["DEFAULT_TEST_RUNNER_PREFIXES", "TestRunnerDetector", "TestTaint"]

#: Package prefixes whose presence in a JVM means a test runner is driving it.
DEFAULT_TEST_RUNNER_PREFIXES: tuple[str, ...] = (
    "org.junit.",
    "junit.framework.",
    "junit.textui.",
    "org.testng.",
    "org.apache.maven.surefire.",
    "org.apache.maven.plugin.surefire.",
    "org.apache.maven.plugin.failsafe.",
    "org.apache.maven.surefire.booter.",
    "org.gradle.api.internal.tasks.testing.",
    "org.gradle.process.internal.worker.",
    "worker.org.gradle.",
    "com.intellij.rt.execution.junit.",
    "com.intellij.rt.junit.",
    "org.jetbrains.plugins.scala.testingSupport.",
    "org.apache.tools.ant.taskdefs.optional.junit.",
    "org.spockframework.",
    "org.scalatest.",
    "io.cucumber.",
    "net.serenitybdd.core.",
    "org.robolectric.",
    "org.mockito.",
    "org.easymock.",
    "org.powermock.",
    "org.springframework.boot.test.",
    "org.springframework.test.context.",
    "io.restassured.",
    "org.testcontainers.",
    "kotlin.test.",
    "io.kotest.",
)


@dataclass(frozen=True, slots=True)
class TestTaint:
    """The outcome of scanning one window for test-runner evidence."""

    tainted: bool
    markers: tuple[str, ...] = ()

    def __bool__(self) -> bool:
        return self.tainted


class TestRunnerDetector:
    """Matches class names against a configurable test-runner catalogue.

    The catalogue is deliberately over-broad. A false positive here costs one
    discarded window (we lose a little liveness evidence and stay in UNKNOWN);
    a false negative permanently marks tested code as alive. The asymmetry is
    the whole point.
    """

    def __init__(self, prefixes: Sequence[str] = DEFAULT_TEST_RUNNER_PREFIXES) -> None:
        self._prefixes = tuple(prefixes)

    @property
    def prefixes(self) -> tuple[str, ...]:
        return self._prefixes

    def matches(self, class_name: str) -> str | None:
        """Return the prefix that matched, or None."""
        for prefix in self._prefixes:
            if class_name.startswith(prefix):
                return prefix
        return None

    def scan(self, class_names: Iterable[str], *, limit: int = 8) -> TestTaint:
        """Scan a set of class names (typically `classesLoaded`) for runners."""
        markers: list[str] = []
        for name in class_names:
            if self.matches(name) is not None:
                markers.append(name)
                if len(markers) >= limit:
                    break
        return TestTaint(bool(markers), tuple(markers))

    def record_tainted(self, cls: str, frames: Iterable[str] = ()) -> TestTaint:
        """C50.3 -- is THIS coverage record carrying a test-runner frame?

        The class itself counts as a frame: a probe reported for
        `org.junit.runners.ParentRunner` is by definition test execution.
        """
        markers: list[str] = []
        if self.matches(cls) is not None:
            markers.append(cls)
        for frame in frames:
            if self.matches(frame) is not None:
                markers.append(frame)
        return TestTaint(bool(markers), tuple(markers))
