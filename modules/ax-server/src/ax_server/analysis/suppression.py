"""CONTRACTS 5 -- the repo-checked-in suppression file (C46), plus the shipped
default list (bug #28).

    com.acme.emergency.KillSwitch#*          # break-glass, exercised ~2yr
    com.acme.batch.YearEndClose#*            # only runs in the year-end window

Break-glass code looks dead by construction. Sensenmann's own example is a
"stop the robot uprising" button pressed every couple of years, which "looks
dead for all intents and purposes"; their fix was one copy-pasted line. Ours is
this file, and it is matched BEFORE any verdict is computed.

Two layers, composed by `compose_suppressions` (bug #28)
-------------------------------------------------------
``user``
    `.auxin/suppress.txt`. Goes FIRST, because matching is first-match-wins,
    so a user pattern always owns the attribution in the verdict's `reasons`.
``default``
    The compiler/Lombok list -- `DEFAULT_SUPPRESSIONS` below, plus the
    manifest-derived rules for `@Generated` members and record accessors that
    a glob cannot express. 28% of the field trial's candidates (5 of 18) were
    undeletable output of this kind.

Every rule carries an `origin`, and `SuppressionRule.provenance` is what
`rules.py` prints, so no suppression can ever fire without saying which list
it came from. `--list-suppressions` enumerates the composed set and
`--no-default-suppressions` drops the default layers entirely. A suppressed
method is `UNKNOWN` and stays IN the output; it is never silently omitted.
"""

import fnmatch
from collections.abc import Iterator, Sequence
from dataclasses import dataclass
from pathlib import Path

from ax_server.analysis.manifest import Manifest
from ax_server.analysis.models import MethodRef

__all__ = [
    "DEFAULT_SUPPRESSIONS",
    "DEFAULT_SOURCE",
    "GENERATED_ANNOTATIONS",
    "MANIFEST_SOURCE",
    "ORIGIN_DEFAULT",
    "ORIGIN_USER",
    "SuppressionRule",
    "Suppressions",
    "compose_suppressions",
    "default_suppressions",
    "manifest_suppressions",
    "parse_suppressions",
]

ORIGIN_USER = "user"
ORIGIN_DEFAULT = "default"
DEFAULT_SOURCE = "<default-suppressions>"
MANIFEST_SOURCE = "<default-suppressions:manifest>"

#: BUG #28. 28% of the field trial's candidates (5 of 18) were undeletable
#: compiler and Lombok output: enum `values()`/`valueOf()`, and
#: `equals`/`hashCode`/`toString` on `@Data` classes. Every one of them is a
#: method the user cannot delete even if they want to -- javac and Lombok put
#: it there -- so proposing it is pure noise, and suppressing it by default is
#: a free precision win.
#:
#: CONTRACTS 5's format VERBATIM, on purpose. A predicate baked into Python
#: would be invisible and unarguable; a rule list is something a user can read
#: (`--list-suppressions`), disagree with, and switch off
#: (`--no-default-suppressions`). "A silent invisible filter is exactly the
#: kind of thing this project keeps finding bugs in."
#:
#: Note how tight the descriptors are. `*#hashCode` would also swallow a
#: hand-written `hashCode(Object)` helper; `*#hashCode()I` is the exact
#: signature javac and Lombok emit and nothing else. Over-suppression is only
#: ever an UNKNOWN, never a wrong deletion -- but an UNKNOWN the user did not
#: ask for is a lost candidate, and lost candidates are this product's whole
#: failure mode.
DEFAULT_SUPPRESSIONS = """\
# auxin default suppressions (bug #28) -- CONTRACTS 5 format.
#
# Compiler- and Lombok-generated members. These cannot be deleted from source
# because they are not IN the source, so nominating them wastes a reviewer's
# attention and nothing else. Turn the whole set off with
# --no-default-suppressions; list it with --list-suppressions.

# -- enum members javac synthesises (JLS 8.9.3) ------------------------
# `values()` returns an array of the enum type; `valueOf(String)` returns it.
# Both descriptors are exact so a hand-written `values()` returning something
# else is still a candidate.
*#values()[L*;                      # javac-generated enum values()
*#valueOf(Ljava/lang/String;)L*;    # javac-generated enum valueOf(String)

# -- Object overrides Lombok writes for @Data / @Value / @EqualsAndHashCode --
# Exact signatures: the three-arg `equals` of a hand-rolled comparator, or a
# `toString(Locale)`, are untouched.
*#equals(Ljava/lang/Object;)Z       # Lombok @Data / @EqualsAndHashCode equals
*#hashCode()I                       # Lombok @Data / @EqualsAndHashCode hashCode
*#toString()Ljava/lang/String;      # Lombok @Data / @ToString toString
*#canEqual(Ljava/lang/Object;)Z     # Lombok's own equals helper, never hand-written

# -- @Builder ----------------------------------------------------------
*#builder()L*;                      # Lombok @Builder factory
*#toBuilder()L*;                    # Lombok @Builder(toBuilder = true)
*$*Builder#*                        # the generated nested builder class itself
"""
# ^ The `*$*Builder#*` line is the BROADEST rule in the set and the first one to
# drop if it ever costs a real candidate: it matches any NESTED class whose name
# ends in `Builder`, which is what Lombok emits (`OrderDto$OrderDtoBuilder`) but
# would also cover a hand-written nested builder. A top-level `FooBuilder` is
# NOT matched -- the `$` is required. Lombok marks these `@Generated`, so once
# ax-static emits method annotations the manifest layer below covers them
# precisely and this line can go.
#
# Same honest caveat on `*#toString()Ljava/lang/String;`: a glob cannot tell
# Lombok's `toString` from a hand-written one. The trial says the generated ones
# dominate, so the default suppresses both -- and a suppressed method is still
# UNKNOWN, still in the output, and still carries a reason naming this list, so
# the cost is visible rather than silent. `--no-default-suppressions` is the
# escape hatch.

#: Annotation simple names that mean "a tool wrote this member". Matched on the
#: SIMPLE name because the same annotation ships under four packages
#: (`javax.annotation`, `jakarta.annotation`, `lombok`, and javac's own
#: `javax.annotation.processing`), and a reader that pins one of them silently
#: misses the other three.
GENERATED_ANNOTATIONS: frozenset[str] = frozenset({"Generated", "javax.annotation.Generated"})


@dataclass(frozen=True, slots=True)
class SuppressionRule:
    class_glob: str
    method_glob: str
    desc_glob: str
    comment: str
    line_no: int
    source: str
    #: BUG #28: WHICH list this rule came from -- `user` or `default`.
    #: Reported in the verdict's `reasons`, because a suppression the user did
    #: not write and cannot see is indistinguishable from a bug.
    origin: str = ORIGIN_USER

    @property
    def pattern(self) -> str:
        base = f"{self.class_glob}#{self.method_glob}"
        return base + self.desc_glob if self.desc_glob != "*" else base

    @property
    def provenance(self) -> str:
        """`"[default list] <default-suppressions>:12"` -- for `reasons`."""
        return f"[{self.origin} list] {self.source}:{self.line_no}"

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

    # -- BUG #28: visibility ---------------------------------------------

    def counts_by_origin(self) -> dict[str, int]:
        """`origin -> rule count`. The headline for `--list-suppressions`."""
        out: dict[str, int] = {}
        for rule in self._rules:
            out[rule.origin] = out.get(rule.origin, 0) + 1
        return dict(sorted(out.items()))

    def listing(self) -> list[dict[str, object]]:
        """Every active rule, in match order, with where it came from.

        The whole set has to be enumerable. A default suppression the user
        cannot list is a filter they cannot audit, and "suppressed for reasons
        you cannot see" is worse than a false positive.
        """
        return [
            {
                "pattern": rule.pattern,
                "origin": rule.origin,
                "source": rule.source,
                "line": rule.line_no,
                "comment": rule.comment,
            }
            for rule in self._rules
        ]

    def render(self) -> str:
        """Human-readable listing for the CLI."""
        lines = [
            f"{len(self._rules)} suppression rule(s): "
            + ", ".join(f"{n} {origin}" for origin, n in self.counts_by_origin().items()),
            "",
        ]
        for rule in self._rules:
            comment = f"   # {rule.comment}" if rule.comment else ""
            lines.append(
                f"  [{rule.origin:<7}] {rule.pattern:<38}{comment}"
                f"\n              {rule.source}:{rule.line_no}"
            )
        if not self._rules:
            lines.append("  (none -- no user file and default suppressions are off)")
        return "\n".join(lines)


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


def parse_suppressions(
    text: str, *, source: str = "<text>", origin: str = ORIGIN_USER
) -> list[SuppressionRule]:
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
                origin=origin,
            )
        )
    return rules


# ======================================================================
# BUG #28 -- the default suppression list
# ======================================================================


def default_suppressions() -> Suppressions:
    """The glob half of the default list -- `DEFAULT_SUPPRESSIONS`, parsed."""
    return Suppressions(
        parse_suppressions(
            DEFAULT_SUPPRESSIONS, source=DEFAULT_SOURCE, origin=ORIGIN_DEFAULT
        )
    )


def _simple_name(annotation: str) -> str:
    """`@lombok.Generated` / `Lorg/x/Generated;` -> `Generated`."""
    text = annotation.strip().lstrip("@").rstrip(";")
    text = text.replace("/", ".")
    if text.startswith("L") and "." in text:
        text = text[1:]
    return text.rpartition(".")[2] or text


def _is_generated(annotations: Sequence[str]) -> bool:
    return any(
        _simple_name(a) in {_simple_name(g) for g in GENERATED_ANNOTATIONS}
        for a in annotations
    )


def manifest_suppressions(manifest: Manifest) -> Suppressions:
    """Default rules that a GLOB CANNOT EXPRESS, derived from the manifest.

    Two of bug #28's four categories are not descriptor-shaped:

    * **`@Generated`-annotated members.** "A tool wrote this" is an annotation
      fact, not a name fact. There is no glob for it.
    * **Record accessors.** `Colour#red()Lj/l/String;` is indistinguishable
      from a hand-written getter by name and descriptor alone; only the
      manifest's record components say which it is.

    So they are derived here -- still as CONTRACTS 5 `SuppressionRule`s, so
    they list, attribute and switch off exactly like the text list does.

    Both manifest fields are ADDITIVE and OPTIONAL (CONTRACTS 1: "every field
    absent in a v1 manifest defaults to its SAFE direction"). A producer that
    emits neither generates no rules here, which is the safe direction for a
    suppression: nothing is hidden that the user did not ask to hide.
    """
    rules: list[SuppressionRule] = []
    line = 0
    for klass in manifest.classes:
        class_generated = _is_generated(klass.annotations)
        if class_generated:
            line += 1
            rules.append(
                SuppressionRule(
                    class_glob=klass.name,
                    method_glob="*",
                    desc_glob="*",
                    comment="@Generated class: every member is tool-written",
                    line_no=line,
                    source=MANIFEST_SOURCE,
                    origin=ORIGIN_DEFAULT,
                )
            )
            # The whole class is covered; per-member rules would be noise in
            # the listing and would never be the first match anyway.
            continue
        components = set(klass.record_components)
        for method in klass.methods:
            reason = ""
            if _is_generated(method.annotations):
                reason = "@Generated member: tool-written, not in the source"
            elif (
                klass.is_record
                and method.name in components
                and method.desc.startswith("()")
            ):
                reason = f"record accessor for component {method.name!r}"
            if not reason:
                continue
            line += 1
            rules.append(
                SuppressionRule(
                    class_glob=klass.name,
                    method_glob=method.name,
                    desc_glob=method.desc or "*",
                    comment=reason,
                    line_no=line,
                    source=MANIFEST_SOURCE,
                    origin=ORIGIN_DEFAULT,
                )
            )
    return Suppressions(rules)


def compose_suppressions(
    user: Suppressions | None = None,
    *,
    manifest: Manifest | None = None,
    include_defaults: bool = True,
) -> Suppressions:
    """Layer the user's `.auxin/suppress.txt` ON TOP OF the defaults.

    Order is USER FIRST, and that is the whole of the override story. `match`
    is first-match-wins, so a user line always decides the attribution: their
    pattern and their comment appear in `reasons`, not ours. The defaults can
    only ever add rules the user's file did not already cover.

    `include_defaults=False` (`--no-default-suppressions`) drops both default
    layers and leaves the user's file exactly as it was before bug #28.
    """
    rules: list[SuppressionRule] = list(user or ())
    if include_defaults:
        # Manifest-derived rules before the glob list: they are exact
        # `Class#name(desc)` matches, so when both fire the listed reason names
        # the specific member rather than a wildcard.
        if manifest is not None:
            rules.extend(manifest_suppressions(manifest))
        rules.extend(default_suppressions())
    return Suppressions(rules)
