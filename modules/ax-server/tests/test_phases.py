"""C45 -- phase-aware windows. Duration alone is not evidence."""

from datetime import UTC, datetime, timedelta

from ax_server.analysis.phases import (
    DEFAULT_REQUIRED_PHASES,
    PhaseCalendar,
    PhaseOccurrence,
    merge_intervals,
    overlap_seconds,
)

from conftest import FIXTURES

D = datetime(2026, 9, 1, tzinfo=UTC)


def test_default_required_phases_match_plan_v2():
    assert DEFAULT_REQUIRED_PHASES == (
        "month-end", "quarter-end", "year-end-close", "peak-season", "dr-drill",
    )


def test_merge_intervals_coalesces():
    merged = merge_intervals(
        [(D, D + timedelta(hours=2)), (D + timedelta(hours=1), D + timedelta(hours=3))]
    )
    assert merged == [(D, D + timedelta(hours=3))]


def test_overlap_seconds():
    union = [(D, D + timedelta(hours=4))]
    assert overlap_seconds(union, (D + timedelta(hours=1), D + timedelta(hours=2))) == 3600


def test_a_phase_that_never_occurred_in_the_span_is_missing():
    cal = PhaseCalendar(
        required=("year-end-close",),
        occurrences=(
            PhaseOccurrence("year-end-close", datetime(2026, 12, 27, tzinfo=UTC),
                            datetime(2027, 1, 1, tzinfo=UTC)),
        ),
        recurring=(),
    )
    cov = cal.evaluate([(D, D + timedelta(days=90))])
    assert cov.missing == ("year-end-close",)
    assert "no occurrence" in cov.detail["year-end-close"]
    assert cov.window_days == 90


def test_a_phase_only_partly_observed_is_missing():
    occ = PhaseOccurrence("month-end", D + timedelta(days=1), D + timedelta(days=3))
    cal = PhaseCalendar(required=("month-end",), occurrences=(occ,), recurring=())
    # Only the first half of the occurrence was observed.
    cov = cal.evaluate([(D, D + timedelta(days=2))])
    assert cov.missing == ("month-end",)
    assert "50%" in cov.detail["month-end"]


def test_a_fully_observed_phase_is_covered():
    occ = PhaseOccurrence("month-end", D + timedelta(days=1), D + timedelta(days=2))
    cal = PhaseCalendar(required=("month-end",), occurrences=(occ,), recurring=())
    cov = cal.evaluate([(D, D + timedelta(days=5))])
    assert cov.covered == ("month-end",)
    assert cov.missing == ()
    assert cov.complete


def test_no_windows_means_every_phase_is_missing():
    cal = PhaseCalendar.default()
    cov = cal.evaluate([])
    assert cov.missing == DEFAULT_REQUIRED_PHASES
    assert cov.window_days == 0
    assert cov.span is None


def test_recurring_month_end_rule_expands():
    cal = PhaseCalendar(
        required=("month-end",),
        recurring=({"phase": "month-end", "rule": "last-days-of-month", "days": 2},),
    )
    span = (datetime(2026, 1, 1, tzinfo=UTC), datetime(2026, 3, 1, tzinfo=UTC))
    occ = [o for o in cal.expand(span) if o.phase == "month-end"]
    labels = {o.label for o in occ}
    assert "2026-01" in labels and "2026-02" in labels
    jan = next(o for o in occ if o.label == "2026-01")
    assert jan.end == datetime(2026, 2, 1, tzinfo=UTC)
    assert jan.start == datetime(2026, 1, 30, tzinfo=UTC)


def test_recurring_quarter_and_year_rules():
    cal = PhaseCalendar(
        required=("quarter-end", "year-end-close"),
        recurring=(
            {"phase": "quarter-end", "rule": "last-days-of-quarter", "days": 3},
            {"phase": "year-end-close", "rule": "last-days-of-year", "days": 5},
        ),
    )
    span = (datetime(2026, 1, 1, tzinfo=UTC), datetime(2027, 1, 1, tzinfo=UTC))
    occ = cal.expand(span)
    quarters = sorted(o.label for o in occ if o.phase == "quarter-end")
    assert quarters == ["2026-03", "2026-06", "2026-09", "2026-12"]
    years = [o.label for o in occ if o.phase == "year-end-close"]
    assert years == ["2026-12"]


def test_calendar_is_configurable_from_a_file():
    cal = PhaseCalendar.from_file(FIXTURES / "phases.json")
    assert cal.required == ("month-end", "quarter-end", "year-end-close")
    assert cal.min_coverage_fraction == 0.9
    labels = {o.label for o in cal.occurrences}
    assert "black-friday-2026" in labels and "dr-2026-h1" in labels


def test_missing_calendar_file_falls_back_to_the_default(tmp_path):
    cal = PhaseCalendar.from_file(tmp_path / "nope.json")
    assert cal.required == DEFAULT_REQUIRED_PHASES
