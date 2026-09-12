"""OR-merge algebra: the property the whole store depends on (A14)."""

import itertools

import pytest

from ax_server.store.bitset import (
    BitsetDecodeError,
    decode_b64,
    from_indices,
    is_set,
    newly_set,
    or_merge,
    popcount,
    set_bits,
)


def test_lsb_is_index_zero():
    # CONTRACTS 2: "base64 of the packed bitset, LSB = idx 0".
    assert from_indices([0]) == b"\x01"
    assert from_indices([7]) == b"\x80"
    assert from_indices([8]) == b"\x00\x01"
    assert decode_b64("AQAB") == b"\x01\x00\x01"
    assert list(set_bits(decode_b64("AQAB"))) == [0, 16]


def test_or_merge_is_idempotent():
    a = from_indices([0, 3, 17])
    assert or_merge(a, a) == a
    assert or_merge(or_merge(a, a), a) == a


def test_or_merge_is_commutative_and_associative():
    a, b, c = from_indices([0, 5]), from_indices([5, 12]), from_indices([31])
    assert or_merge(a, b) == or_merge(b, a)
    assert or_merge(or_merge(a, b), c) == or_merge(a, or_merge(b, c))


def test_or_merge_order_independent_over_every_permutation():
    parts = [from_indices([0]), from_indices([9]), from_indices([2, 40]), from_indices([])]
    results = set()
    for perm in itertools.permutations(parts):
        acc = b""
        for p in perm:
            acc = or_merge(acc, p)
        results.add(acc)
    assert len(results) == 1
    assert popcount(results.pop()) == 4


def test_or_merge_never_truncates_on_a_shorter_incoming():
    wide = from_indices([40])
    narrow = from_indices([0])
    merged = or_merge(wide, narrow)
    assert is_set(merged, 40) and is_set(merged, 0)
    assert len(merged) == len(wide)


def test_or_merge_never_overwrites():
    existing = from_indices([1, 2, 3])
    incoming = from_indices([4])
    merged = or_merge(existing, incoming)
    assert sorted(set_bits(merged)) == [1, 2, 3, 4]


def test_newly_set_is_the_delta():
    assert newly_set(from_indices([0, 1]), from_indices([0, 1, 2])) == [2]
    assert newly_set(from_indices([0, 1]), from_indices([0])) == []


def test_out_of_range_read_is_false_not_an_error():
    assert is_set(b"", 0) is False
    assert is_set(b"\x01", 9999) is False


def test_bad_base64_is_rejected_loudly():
    with pytest.raises(BitsetDecodeError):
        decode_b64("not!base64!")
