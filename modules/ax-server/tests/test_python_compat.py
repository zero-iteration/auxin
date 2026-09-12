"""BUG #27 -- `requires-python` must stay at the version we actually need.

    "`requires-python = ">=3.14"` blocks install on essentially every current
    box. It runs fine on 3.12."

They are right, and the floor was never earned: nothing in `src/` uses a
feature newer than 3.11 (`datetime.UTC`, `enum.StrEnum`), and the newest
runtime call is `int.bit_count` (3.10). A version floor is a claim about the
code, so this file ENFORCES the claim rather than restating it -- otherwise
the next accidental 3.14-only call re-introduces #27 silently and nobody finds
out until a user cannot install.

The scan is a deliberately CRUDE AST walk over the real source. It cannot
prove 3.12 compatibility (only a 3.12 interpreter can do that, and CI should
run one) but it catches every way we have actually broken it: a syntax
construct 3.12 cannot parse, or a name from a post-3.12 stdlib addition.
"""

import ast
import pathlib
import re
import sys

import pytest

SRC = pathlib.Path(__file__).resolve().parents[1] / "src" / "ax_server"
PYPROJECT = pathlib.Path(__file__).resolve().parents[1] / "pyproject.toml"

#: The floor we claim. Bump BOTH this and pyproject.toml, together, and only
#: with a feature in hand that actually needs it.
FLOOR = (3, 12)


def _modules() -> list[pathlib.Path]:
    return sorted(SRC.rglob("*.py"))


def test_requires_python_is_the_floor_we_claim():
    text = PYPROJECT.read_text(encoding="utf-8")
    match = re.search(r'requires-python\s*=\s*"([^"]+)"', text)
    assert match, "pyproject.toml has no requires-python"
    assert match.group(1) == ">=%d.%d" % FLOOR


# ---------------------------------------------------------------------
# syntax
# ---------------------------------------------------------------------

#: AST node types that do not exist, or cannot be parsed, before 3.13/3.14.
#: PEP 695 (`type X = ...`, `def f[T]()`, `class C[T]`) is 3.12, so it would be
#: legal -- but PEP 696 type-parameter DEFAULTS are 3.13 and are expressed on
#: the same nodes, so any type parameter is checked for a default below.
_PEP695_NODES = ("TypeAlias", "TypeVar", "ParamSpec", "TypeVarTuple")


def _post_floor_syntax(tree: ast.AST) -> list[str]:
    found: list[str] = []
    for node in ast.walk(tree):
        name = type(node).__name__
        # PEP 750 t-strings (3.14).
        if name in ("TemplateStr", "Interpolation"):
            found.append(f"{name} (t-string, 3.14)")
        # PEP 696 type parameter defaults (3.13).
        if name in _PEP695_NODES and getattr(node, "default_value", None) is not None:
            found.append(f"{name} default (PEP 696, 3.13)")
    return found


def test_no_module_uses_syntax_newer_than_the_floor():
    offenders: dict[str, list[str]] = {}
    for path in _modules():
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        found = _post_floor_syntax(tree)
        if found:
            offenders[str(path.relative_to(SRC))] = found
    assert offenders == {}


def test_no_module_uses_unparenthesised_multi_except():
    """PEP 758 (`except A, B:` without parentheses) is 3.14-only syntax.

    It parses here, so an AST walk cannot see it -- but a 3.12 interpreter
    would raise SyntaxError at import, which is exactly the #27 failure mode.
    """
    pattern = re.compile(r"^\s*except\s+[^(\n]+,[^)\n]+:", re.MULTILINE)
    offenders = [
        str(p.relative_to(SRC))
        for p in _modules()
        if pattern.search(p.read_text(encoding="utf-8"))
    ]
    assert offenders == []


# ---------------------------------------------------------------------
# stdlib surface
# ---------------------------------------------------------------------

#: Names added to the stdlib AFTER our floor. Referencing one of these compiles
#: fine on 3.14 and raises AttributeError/ImportError on 3.12 -- a runtime
#: break that no syntax check catches. Kept as a list of the plausible ones for
#: THIS codebase's imports (abc, argparse, ast, base64, collections,
#: contextlib, dataclasses, datetime, enum, fnmatch, gzip, http, json, logging,
#: math, pathlib, re, sqlite3, threading, typing, urllib, zlib) rather than a
#: pretence at completeness.
POST_FLOOR_NAMES: frozenset[str] = frozenset(
    {
        # -- 3.13 --
        "TypeIs",
        "ReadOnly",
        "NoDefault",
        "get_protocol_members",
        "is_protocol",
        "PythonFinalizationError",
        "process_cpu_count",
        "from_uri",
        "full_match",
        "Doc",
        # -- 3.14 --
        "annotationlib",
        "TemplateStr",
        "Interpolation",
        "interpreters",
        "zstd",
        "Sentinel",
    }
)


def _referenced_names(tree: ast.AST) -> set[str]:
    out: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Attribute):
            out.add(node.attr)
        elif isinstance(node, ast.Name):
            out.add(node.id)
        elif isinstance(node, ast.ImportFrom):
            out.update(a.name for a in node.names)
        elif isinstance(node, ast.Import):
            out.update(a.name for a in node.names)
    return out


def test_no_module_references_a_post_floor_stdlib_name():
    offenders: dict[str, set[str]] = {}
    for path in _modules():
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        hits = _referenced_names(tree) & POST_FLOOR_NAMES
        if hits:
            offenders[str(path.relative_to(SRC))] = hits
    assert offenders == {}


def test_every_module_compiles_on_this_interpreter():
    """Belt and braces: compile every module, no exclusions.

    In memory rather than via `compileall`, so the test leaves no `.pyc`
    behind. On an interpreter at or above the floor this proves the source
    parses; the real 3.12 check is the subprocess test below.
    """
    for path in _modules():
        compile(path.read_text(encoding="utf-8"), str(path), "exec", dont_inherit=True)


def test_it_actually_compiles_under_python312_when_one_is_available():
    """The only check that PROVES #27 is fixed: a real 3.12 interpreter.

    Skipped, loudly, when no python3.12 is on PATH -- which is the case on the
    machine this was written on, so the AST scans above are what stands in for
    it. CI should provide one.
    """
    import shutil
    import subprocess

    exe = shutil.which("python3.12")
    if exe is None:
        pytest.skip(
            "no python3.12 on PATH; the AST scans above are the portable "
            "approximation. Install 3.12 in CI to make this the real check."
        )
    proc = subprocess.run(
        [exe, "-m", "compileall", "-q", "-f", str(SRC)],
        capture_output=True,
        text=True,
        check=False,
    )
    assert proc.returncode == 0, proc.stdout + proc.stderr


@pytest.mark.skipif(
    sys.version_info[:2] != FLOOR,
    reason=(
        "only meaningful ON the floor interpreter; the AST scans above are the "
        "portable approximation. Run the suite under python3.12 in CI to make "
        "this the real check."
    ),
)
def test_the_package_imports_on_the_floor_interpreter():
    import importlib

    for path in _modules():
        rel = path.relative_to(SRC).with_suffix("")
        parts = [p for p in rel.parts if p != "__init__"]
        if parts and parts[-1] == "__main__":
            continue  # argparse at import time
        importlib.import_module(".".join(["ax_server", *parts]))
