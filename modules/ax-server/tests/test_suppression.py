"""CONTRACTS 5 -- suppression file parsing and matching (C46)."""

from ax_server.analysis.models import MethodRef
from ax_server.analysis.suppression import Suppressions

from conftest import FIXTURES


def test_parses_the_contract_example():
    s = Suppressions.from_file(FIXTURES / "suppress.txt")
    assert len(s) == 2
    rules = list(s.rules)
    assert rules[0].class_glob == "com.acme.emergency.KillSwitch"
    assert rules[0].method_glob == "*"
    assert rules[0].comment == "break-glass, exercised ~2yr"


def test_comment_and_separator_hashes_are_distinguished():
    s = Suppressions.from_text("com.acme.A#b   # a comment with # inside\n# whole line\n")
    assert len(s) == 1
    rule = list(s.rules)[0]
    assert rule.class_glob == "com.acme.A"
    assert rule.method_glob == "b"
    assert rule.comment.startswith("a comment")


def test_blank_lines_and_comments_are_skipped():
    assert len(Suppressions.from_text("\n\n  \n# only a comment\n")) == 0


def test_matching_is_by_class_and_method_glob():
    s = Suppressions.from_text("com.acme.emergency.KillSwitch#*\n")
    assert s.suppressed(MethodRef("com.acme.emergency.KillSwitch", "trip", "()V"))
    assert not s.suppressed(MethodRef("com.acme.emergency.Other", "trip", "()V"))


def test_package_wildcards_cross_dots():
    s = Suppressions.from_text("com.acme.batch.*#*\n")
    assert s.suppressed(MethodRef("com.acme.batch.deep.YearEndClose", "run", "()V"))


def test_method_glob_narrows():
    s = Suppressions.from_text("com.acme.A#handle*\n")
    assert s.suppressed(MethodRef("com.acme.A", "handleFoo", "()V"))
    assert not s.suppressed(MethodRef("com.acme.A", "other", "()V"))


def test_descriptor_may_be_pinned():
    s = Suppressions.from_text("com.acme.A#m(I)V\n")
    assert s.suppressed(MethodRef("com.acme.A", "m", "(I)V"))
    assert not s.suppressed(MethodRef("com.acme.A", "m", "(J)V"))


def test_bare_class_suppresses_every_method():
    s = Suppressions.from_text("com.acme.A\n")
    assert s.suppressed(MethodRef("com.acme.A", "anything", "()V"))


def test_missing_file_is_an_empty_ruleset_not_a_crash(tmp_path):
    assert len(Suppressions.from_file(tmp_path / "nope.txt")) == 0


def test_match_reports_which_rule_fired():
    s = Suppressions.from_text("com.acme.A#*  # why\ncom.acme.B#*\n")
    rule = s.match(MethodRef("com.acme.A", "x", "()V"))
    assert rule is not None and rule.line_no == 1 and rule.comment == "why"
