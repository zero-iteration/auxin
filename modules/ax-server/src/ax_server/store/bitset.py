"""Packed probe bitsets.

CONTRACTS 2: `probes` is base64 of the packed bitset, **LSB = idx 0**.
So byte 0 bit 0 is probe 0, byte 0 bit 7 is probe 7, byte 1 bit 0 is probe 8.

Every function here is pure. The merge operation is bitwise OR and nothing
else: OR is idempotent (a|a == a), commutative (a|b == b|a) and associative,
which is exactly why it is safe to apply across pods, restarts and out-of-order
flushes (VALIDATION.md A14: "union is sound").
"""

import base64
import binascii
from collections.abc import Iterable, Iterator

__all__ = [
    "BitsetDecodeError",
    "decode_b64",
    "encode_b64",
    "from_indices",
    "is_set",
    "newly_set",
    "or_merge",
    "popcount",
    "set_bits",
]


class BitsetDecodeError(ValueError):
    """The base64 probe payload could not be decoded."""


def decode_b64(value: str) -> bytes:
    """Decode a base64 probe bitset. Strict: padding and alphabet are checked."""
    if not isinstance(value, str):
        raise BitsetDecodeError(f"probes must be a base64 string, got {type(value).__name__}")
    try:
        return base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise BitsetDecodeError(f"probes is not valid base64: {exc}") from exc


def encode_b64(buf: bytes) -> str:
    """Encode a packed bitset back to base64 (used by tests and fixtures)."""
    return base64.b64encode(buf).decode("ascii")


def or_merge(existing: bytes, incoming: bytes) -> bytes:
    """Bitwise-OR two bitsets, widening to the longer of the two.

    NEVER an overwrite. A shorter incoming bitset must not truncate stored
    coverage, and a longer one must not be clipped.
    """
    if len(existing) < len(incoming):
        existing, incoming = incoming, existing
    out = bytearray(existing)
    for i, byte in enumerate(incoming):
        out[i] |= byte
    return bytes(out)


def is_set(buf: bytes, idx: int) -> bool:
    """True if probe `idx` is set. Out-of-range reads are False, never an error."""
    if idx < 0:
        raise ValueError(f"probe index must be non-negative, got {idx}")
    byte = idx >> 3
    if byte >= len(buf):
        return False
    return bool(buf[byte] & (1 << (idx & 7)))


def set_bits(buf: bytes) -> Iterator[int]:
    """Yield the indices of every set probe, ascending."""
    for byte_index, byte in enumerate(buf):
        if not byte:
            continue
        base = byte_index << 3
        for bit in range(8):
            if byte & (1 << bit):
                yield base + bit


def popcount(buf: bytes) -> int:
    """Number of set probes."""
    return sum(byte.bit_count() for byte in buf)


def newly_set(existing: bytes, incoming: bytes) -> list[int]:
    """Indices set in `incoming` that were not already set in `existing`.

    This is the delta the collector computes (CONTRACTS 2: probes are
    "Accumulated, never reset -- the collector computes deltas").
    """
    return [idx for idx in set_bits(incoming) if not is_set(existing, idx)]


def from_indices(indices: Iterable[int], *, size_bytes: int | None = None) -> bytes:
    """Build a packed bitset from probe indices. Test/fixture helper."""
    idx_list = list(indices)
    needed = (max(idx_list) >> 3) + 1 if idx_list else 0
    width = max(needed, size_bytes or 0)
    buf = bytearray(width)
    for idx in idx_list:
        if idx < 0:
            raise ValueError(f"probe index must be non-negative, got {idx}")
        buf[idx >> 3] |= 1 << (idx & 7)
    return bytes(buf)
