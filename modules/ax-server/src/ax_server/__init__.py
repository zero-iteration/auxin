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

__all__ = [
    "CONTRACTS_2_ADDITIONS",
    "CONTRACTS_4_AMENDMENT",
    "SCHEMA_VERSION",
    "__version__",
]

__version__ = "0.2.0"

#: The only `schemaVersion` the collector accepts on the wire (CONTRACTS 2).
SCHEMA_VERSION = 2
"""The wire schemaVersion this collector EMITS in acks and treats as current.

Bug #17: the agent has sent 2 since the C50 liveness fields landed, while this constant said 1
and ``decode.py`` refused anything ``!= SCHEMA_VERSION`` -- so the real agent's every window was
rejected by the real collector. Neither side's tests caught it: the agent flushes to a throwaway
listener that accepts anything, and these tests used fixtures hardcoded to 1. Two components, each
verified against a stand-in for the other.
"""

SUPPORTED_SCHEMA_VERSIONS = frozenset({1, 2})
"""Wire versions this collector can read.

A *set*, not a single constant, because the additions across v1->v2 (C50's ``environment`` /
``livenessEvidence`` / ``testRunnerDetected``, and v3-contract ``edges[]``) are purely additive:
a reader that ignores unknown keys decodes an older or newer-but-additive body correctly. The
refusal still exists and still matters -- an UNKNOWN version may carry a different probe-index
assignment (A14 defect 2), and accepting that silently is how coverage gets attributed to the
wrong methods. So we refuse what we do not know, and we now actually know 2.
"""

CONTRACTS_4_AMENDMENT = (
    "Verdict.status adds NOT_DYNAMICALLY_OBSERVABLE (C51); Verdict adds "
    "eligibility (C51), first_proposed_at and revoked_reason (C53). "
    "Verdict.eligibility adds a FOURTH value, NO_PROBE_INSTALLED, plus the "
    "fields probe_installed (True|False|None) and probe_install_mask_reported "
    "(bug #18): the manifest cannot express a per-JVM frameEmissionUnsupported "
    "skip or ax.tier1.enabled=false, so 'observable but never instrumented' is "
    "a runtime eligibility class and NOT a status. status stays the same four "
    "values. CONTRACTS.md section 4 is marked FROZEN and must be "
    "version-bumped to match."
)

CONTRACTS_2_ADDITIONS = (
    "coverage[].probesInstalled (base64, packed exactly like probes) and "
    "agentHealth.stripMaskMissing (counter). ADDITIVE: the wire schemaVersion "
    "stays 2 because the collector reads only named keys, and bumping it to "
    "announce an additive field would make every deployed collector drop every "
    "window -- bug #17's lesson. Reading rule: probes => liveness; "
    "probesInstalled & ~probes => the ONLY death evidence; ~probesInstalled => "
    "silence, never evidence of anything (bug #18)."
)
