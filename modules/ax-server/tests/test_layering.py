"""The one-way dependency rule is a property of the build, so test it."""

import ast
import pathlib

import pytest

SRC = pathlib.Path(__file__).resolve().parents[1] / "src" / "ax_server"
ORDER = ["store", "collector", "analysis", "api", "mcp"]


def _imports(path: pathlib.Path) -> set[str]:
    tree = ast.parse(path.read_text(encoding="utf-8"))
    out: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom) and node.module:
            out.add(node.module)
        elif isinstance(node, ast.Import):
            out.update(a.name for a in node.names)
    return out


@pytest.mark.parametrize("layer", ORDER)
def test_no_layer_imports_a_higher_one(layer):
    higher = ORDER[ORDER.index(layer) + 1 :]
    for path in (SRC / layer).rglob("*.py"):
        for module in _imports(path):
            for up in higher:
                assert not module.startswith(f"ax_server.{up}"), (
                    f"{path.relative_to(SRC)} imports {module}: "
                    f"{layer} must not depend on {up}"
                )


def test_store_depends_on_nothing_above_itself():
    for path in (SRC / "store").rglob("*.py"):
        for module in _imports(path):
            if module.startswith("ax_server"):
                assert module.startswith("ax_server.store"), f"{path}: {module}"


def test_every_layer_exists_and_is_a_package():
    for layer in ORDER:
        assert (SRC / layer / "__init__.py").is_file()


def test_no_third_party_dependency_crept_in():
    stdlib_ok = {"ax_server"}
    for path in SRC.rglob("*.py"):
        for module in _imports(path):
            root = module.split(".")[0]
            if root in stdlib_ok:
                continue
            assert root in _STDLIB, f"{path}: unexpected dependency {root!r}"


_STDLIB = {
    "abc", "argparse", "ast", "base64", "binascii", "calendar", "collections",
    "contextlib", "dataclasses", "datetime", "enum", "fnmatch", "gzip", "http",
    "io", "json", "logging", "math", "pathlib", "re", "sqlite3", "sys",
    "threading", "types", "typing", "urllib", "zlib",
}
