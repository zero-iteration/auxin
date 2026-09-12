"""CONTRACTS 5 -- the repo-checked-in suppression file (C46).

    com.acme.emergency.KillSwitch#*          # break-glass, exercised ~2yr
    com.acme.batch.YearEndClose#*            # only runs in the year-end window

Break-glass code looks dead by construction. Sensenmann's own example is a
"stop the robot uprising" button pressed every couple of years, which "looks
dead for all intents and purposes"; their fix was one copy-pasted line. Ours is
this file, and it is matched BEFORE any verdict is computed.
"""

import fnmatch
from collections.abc import Iterator, Sequence
from dataclasses import dataclass
from pathlib import Path

from ax_server.analysis.models import MethodRef

__all__ = ["SuppressionRule", "Suppressions", "parse_suppressions"]


@dataclass(frozen=True, slots=True)
class SuppressionRule:
    class_glob: str
    method_glob: str
    desc_glob: str
    comment: str
    line_no: int
    source: str

    @property
    def pattern(self) -> str:
        base = f"{self.class_glob}#{self.method_glob}"
        return base + self.desc_glob if self.desc_glob != "*" else base

    def matches(self, ref: MethodRef) -> bool:
        if not fnmatch.fnmatchcase(ref.cls, self.class_glob):
            return False
        if not fnmatch.fnmatchcase(ref.name, self.method_glob):
            return False
        if self.desc_glob == "*":
            return True
        return fnmatch.fnmatchcase(ref.desc, self.desc_glob)


class Suppressions:
    """An ordered rule set. First match wins, so the file reads top-down."""

    def __init__(self, rules: Sequence[SuppressionRule] = ()) -> None:
        self._rules = tuple(rules)

    def __len__(self) -> int:
        return len(self._rules)

    def __iter__(self) -> Iterator[SuppressionRule]:
        return iter(self._rules)

    @property
    def rules(self) -> tuple[SuppressionRule, ...]:
        return self._rules

    def match(self, ref: MethodRef) -> SuppressionRule | None:
        for rule in self._rules:
            if rule.matches(ref):
                return rule
        return None

    def suppressed(self, ref: MethodRef) -> bool:
        return self.match(ref) is not None

    @classmethod
    def from_file(cls, path: str | Path) -> "Suppressions":
        p = Path(path)
        if not p.exists():
            return cls(())
        return cls(parse_suppressions(p.read_text(encoding="utf-8"), source=str(p)))

    @classmethod
    def from_text(cls, text: str, *, source: str = "<text>") -> "Suppressions":
        return cls(parse_suppressions(text, source=source))


def _split_comment(line: str) -> tuple[str, str]:
    """Separate the pattern from the trailing comment.

    `#` is both the class/method separator AND the comment marker, so the rule
    is positional: a `#` at the start of the line or preceded by whitespace
    opens a comment; a `#` glued to the pattern is the separator.
    """
    if line.lstrip().startswith("#"):
        return "", line.lstrip()[1:].strip()
    for i, ch in enumerate(line):
        if ch == "#" and i > 0 and line[i - 1].isspace():
            return line[:i].strip(), line[i + 1 :].strip()
    return line.strip(), ""


def parse_suppressions(text: str, *, source: str = "<text>") -> list[SuppressionRule]:
    rules: list[SuppressionRule] = []
    for line_no, raw in enumerate(text.splitlines(), start=1):
        pattern, comment = _split_comment(raw)
        if not pattern:
            continue
        if "#" in pattern:
            class_glob, rest = pattern.split("#", 1)
        else:
            class_glob, rest = pattern, "*"
        class_glob = class_glob.strip() or "*"
        rest = rest.strip() or "*"
        if "(" in rest:
            method_glob, desc = rest.split("(", 1)
            desc_glob = "(" + desc
        else:
            method_glob, desc_glob = rest, "*"
        rules.append(
            SuppressionRule(
                class_glob=class_glob,
                method_glob=method_glob.strip() or "*",
                desc_glob=desc_glob.strip() or "*",
                comment=comment,
                line_no=line_no,
                source=source,
            )
        )
    return rules
