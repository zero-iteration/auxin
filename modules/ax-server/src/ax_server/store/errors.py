"""Store-layer errors."""


class StoreError(Exception):
    """Base class for every error raised by the store layer."""


class SchemaMismatch(StoreError):
    """Raised when a coverage merge is attempted with the wrong `schemaHash`.

    CONTRACTS 3: "Mismatched `schema_hash` raises `SchemaMismatch` - it must
    not silently merge or silently report zero."

    The silent-zero variant of this failure is a documented JaCoCo defect
    (VALIDATION.md A14 defect 1): JaCoCo keys on a CRC64 of the raw class
    bytes, so another agent transforming first makes merges either fail or
    report 0% coverage -- which reads exactly like "this code is dead".
    Reporting 0% here would manufacture false DEAD_CANDIDATEs, so we raise
    instead, loudly, and drop the record.
    """

    def __init__(self, build_sha: str, cls: str, expected: str, actual: str) -> None:
        self.build_sha = build_sha
        self.cls = cls
        self.expected = expected
        self.actual = actual
        super().__init__(
            f"schemaHash mismatch for {cls} @ {build_sha}: "
            f"stored={expected!r} incoming={actual!r}; refusing to merge "
            f"(a silent merge or a silent 0% would fabricate dead code)"
        )
