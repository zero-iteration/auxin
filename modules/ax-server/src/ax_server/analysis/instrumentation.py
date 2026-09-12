"""Which probe indices were ACTUALLY INSTALLED -- the third state of a bit.

This module exists to hold one rule, and the rule is the whole reason it is
separate from the coverage bitset it sits next to:

    **A ZERO BIT AT AN UNINSTRUMENTED INDEX IS SILENCE, NOT AN OBSERVATION.**

Bug #18. The probe array is sized to the manifest's ``probeCount`` -- every
probe-eligible method -- while the emitter installs a probe at only *some* of
those indices. An index with no probe is never written by anything, so its bit
is permanently zero, and it was being shipped in ``coverage[].probes``
alongside genuinely-zero bits: as evidence that a method never ran.

Some skips are recoverable from the manifest (``dynamicallyObservable: false``
-> C51, already mapped to ``NOT_DYNAMICALLY_OBSERVABLE``). The rest are
**not expressible in a manifest at all**:

* ``frameEmissionUnsupported`` -- a per-method decision about *this* JVM's
  bytecode shape, which another JVM in the same fleet may not share;
* ``ax.tier1.enabled=false`` -- every bitset all-zero while every method stays
  ``dynamicallyObservable: true``.

So before this, a window from a JVM with tier-1 disabled looked exactly like
"every method in your codebase is dead".

The reading rule, in full
-------------------------
::

    probes                        -> liveness (a set bit means it ran)
    probesInstalled & ~probes     -> the ONLY death evidence
    ~probesInstalled              -> SILENCE. not evidence of anything.

Three states, not two
---------------------
``installed()`` returns ``True | False | None`` and the third value is load
bearing:

    True   a probe really exists at this index in some JVM, so a zero bit is a
           real observation of non-execution.
    False  NO JVM ever installed a probe here. The bit is zero because nothing
           can write it. This is the case that was silently reading as dead.
    None   no agent ever declared a mask for this class -- a pre-#18 producer.
           The clause ABSTAINS: it neither invents an installation nor
           withdraws one, so such a build keeps exactly the behaviour it had.

A boolean cannot carry that, which is why there is no ``installed: bool`` here
and why every read in ``rules.py`` is an explicit ``is True`` / ``is False`` /
``is None`` (an AST test pins that). Collapsing None into False silently
deletes every candidate in a build and says nothing; collapsing False into
None is bug #18 itself.

When the agent cannot determine the mask it ships an ALL-ZERO one --
``agentHealth.stripMaskMissing`` counts it -- **losing a candidate, never
inventing one**. This module preserves that bias: everything here can only
ever withhold death evidence, never manufacture it.
"""

from collections.abc import Mapping
from dataclasses import dataclass, field

from ax_server.store.bitset import and_not, is_set, popcount, set_bits
from ax_server.store.port import ProbeInstallStore, Store

__all__ = [
    "InstalledProbes",
    "load_installed_probes",
]


@dataclass(frozen=True, slots=True)
class InstalledProbes:
    """A build's merged installed-probe masks, read once per analysis run.

    The masks are the **bitwise OR across pods and windows** (see
    ``ProbeInstallStore``): different JVMs legitimately install different
    probe sets, and the union is the conservative answer to the only question
    asked here -- "could SOME JVM have observed this index?"
    """

    build_sha: str = ""
    #: `class -> merged mask`. A class ABSENT from this mapping never reported
    #: a mask; a class PRESENT with an all-zero mask reported that it
    #: installed nothing. Those are different facts -- never use `.get(cls)`
    #: truthiness to tell them apart, because an all-zero mask can be b"".
    masks: Mapping[str, bytes] = field(default_factory=dict)
    #: Whether the store adapter can hold a mask at all. False means the #18
    #: gate is not running for structural reasons, which is worth saying out
    #: loud rather than reporting as "no agent sent one".
    supported: bool = False

    # -- the tri-state read ----------------------------------------------

    def installed(self, cls: str, idx: int) -> bool | None:
        """Was a probe installed at `(cls, idx)`?

        True / False / None, where None means NO MASK WAS EVER REPORTED for
        this class and the caller must abstain. See the module docstring: the
        three states are not interchangeable.
        """
        mask = self.masks.get(cls)
        if mask is None:
            return None
        if idx < 0:
            return None
        return is_set(mask, idx)

    def reported_for(self, cls: str) -> bool:
        """True when some window declared a mask for this class."""
        return cls in self.masks

    @property
    def reported(self) -> bool:
        """True when some window declared a mask for ANY class in the build."""
        return bool(self.masks)

    @property
    def classes_reported(self) -> int:
        return len(self.masks)

    @property
    def installed_indices(self) -> int:
        """Total indices covered by every reported mask.

        Zero WITH `reported` true is the `ax.tier1.enabled=false` signature:
        windows arrived, they declared a mask, and the mask says nothing in
        this JVM was instrumented at all. That is a configuration fact, and it
        is the one an operator must not read as "the codebase is dead".
        """
        return sum(popcount(mask) for mask in self.masks.values())

    @property
    def classes_with_nothing_installed(self) -> int:
        return sum(1 for mask in self.masks.values() if popcount(mask) == 0)

    def installed_count(self, cls: str) -> int | None:
        """How many indices the merged mask covers, or None if unreported."""
        mask = self.masks.get(cls)
        if mask is None:
            return None
        return popcount(mask)

    def death_evidence_indices(self, cls: str, probes: bytes) -> list[int] | None:
        """`probesInstalled & ~probes` -- the ONLY indices this class can argue
        death for. None when no mask was reported.

        Provided so no caller has to re-derive the expression, because the two
        wrong versions of it (`~probes` alone, or `~probesInstalled`) are
        exactly bug #18 and its mirror image.
        """
        mask = self.masks.get(cls)
        if mask is None:
            return None
        return list(set_bits(and_not(mask, probes)))

    # -- disclosure ------------------------------------------------------

    def caveat(self) -> str:
        """The sentence that must accompany any answer built from a mask."""
        if not self.supported:
            return (
                "This store cannot hold an installed-probe mask, so every index was "
                "treated as 'no mask reported' and the bug #18 gate did not run. An "
                "all-zero coverage bitset from a JVM with tier-1 disabled would be "
                "indistinguishable from dead code."
            )
        if not self.reported:
            return (
                "No window reported coverage[].probesInstalled for this build (a pre-#18 "
                "agent), so nothing is known about which probe indices were actually "
                "instrumented. Verdicts here are computed WITHOUT the bug #18 gate: an "
                "uninstrumented index still looks like an unexecuted one."
            )
        return (
            "probes => liveness (a set bit means it ran). probesInstalled & ~probes => the "
            "ONLY death evidence. ~probesInstalled => SILENCE: an index with no probe is "
            "never written by anything, so its zero bit says nothing about the method "
            "(bug #18). A JVM with ax.tier1.enabled=false reports every bit zero while "
            "every method stays dynamicallyObservable: true."
        )


def load_installed_probes(store: Store, build_sha: str) -> InstalledProbes:
    """Read the merged masks for a build.

    An adapter without `ProbeInstallStore` yields `supported=False` and no
    masks, which every reader must treat as "nothing is known" -- the same
    state a pre-#18 agent produces, and never as "nothing was installed".
    """
    if not isinstance(store, ProbeInstallStore):
        return InstalledProbes(build_sha=build_sha, supported=False)
    return InstalledProbes(
        build_sha=build_sha,
        masks=dict(store.installed_masks(build_sha)),
        supported=True,
    )
