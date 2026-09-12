"""auxin server side.

Five packages with STRICT one-way dependencies:

    store  ->  collector  ->  analysis  ->  api  ->  mcp

A lower layer must never import a higher one. `store` defines the port
(CONTRACTS.md 3); SQLite is an adapter behind it.

Contract versions implemented here
----------------------------------
CONTRACTS 2 (wire), 3 (store port), 5 (suppression) are implemented verbatim.

CONTRACTS 4 (Verdict) is implemented with a DOCUMENTED AMENDMENT: the frozen
`status` Literal gains a fourth value, ``NOT_DYNAMICALLY_OBSERVABLE``, and the
dataclass gains ``eligibility``, ``first_proposed_at`` and ``revoked_reason``.
This is required by VALIDATION.md C51 (correctness bug) and C53, which postdate
the freeze. It is a breaking change to 4 and needs the contract version bumped;
see ``CONTRACTS_4_AMENDMENT`` below.
"""

__all__ = ["SCHEMA_VERSION", "CONTRACTS_4_AMENDMENT", "__version__"]

__version__ = "0.2.0"

#: The only `schemaVersion` the collector accepts on the wire (CONTRACTS 2).
SCHEMA_VERSION = 1

CONTRACTS_4_AMENDMENT = (
    "Verdict.status adds NOT_DYNAMICALLY_OBSERVABLE (C51); Verdict adds "
    "eligibility (C51), first_proposed_at and revoked_reason (C53). "
    "CONTRACTS.md section 4 is marked FROZEN and must be version-bumped to "
    "match."
)
