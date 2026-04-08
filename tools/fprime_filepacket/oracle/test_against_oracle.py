"""L1 cross-implementation oracle test.

Compares the Python codec's encoder output against golden byte vectors
produced by F´'s own C++ encoder. This proves bit-for-bit agreement
between the two implementations on the wire format.

Prerequisites: the C++ oracle program must have been built and run:

    cd FprimeYamcsReference/YamcsDeployment && fprime-util generate && fprime-util build
    bash tools/fprime_filepacket/oracle/build.sh
    tools/fprime_filepacket/oracle/dump_vectors tools/fprime_filepacket/oracle/vectors

Run:

    PYTHONPATH=tools python3 tools/fprime_filepacket/oracle/test_against_oracle.py
"""

from __future__ import annotations

from pathlib import Path

from fprime_filepacket import (
    CancelPacket,
    DataPacket,
    EndPacket,
    StartPacket,
)


VECTORS_DIR = Path(__file__).parent / "vectors"


def _read_vector(name: str) -> bytes:
    return (VECTORS_DIR / name).read_bytes()


def _assert_prefix(actual: bytes, expected_prefix: bytes, label: str) -> None:
    """The C++ oracle dumps full buffer storage (capacity), not just the
    serialized prefix, so trailing bytes are zero padding. Compare only the
    first len(expected_prefix) bytes.
    """
    head = actual[: len(expected_prefix)]
    if head != expected_prefix:
        raise AssertionError(
            f"{label}: mismatch\n"
            f"  expected: {expected_prefix.hex(' ')}\n"
            f"  actual:   {head.hex(' ')}"
        )


def test_start_matches_oracle():
    py = StartPacket(
        sequence_index=0,
        file_size=100,
        source_path="a",
        destination_path="b",
    ).to_bytes()
    fpr = _read_vector("start_seq0_size100_a_b.bin")
    _assert_prefix(fpr, py, "StartPacket")


def test_data_matches_oracle():
    py = DataPacket(
        sequence_index=1,
        byte_offset=0,
        data=b"hello",
    ).to_bytes()
    fpr = _read_vector("data_seq1_off0_hello.bin")
    _assert_prefix(fpr, py, "DataPacket")


def test_end_matches_oracle():
    # F´ computed CFDP modular checksum of "hello" = 0xd7656c6c.
    # Verified independently by tools/fprime_filepacket/test_codec.py.
    py = EndPacket(
        sequence_index=2,
        checksum=0xD7656C6C,
    ).to_bytes()
    fpr = _read_vector("end_seq2_hello_checksum.bin")
    _assert_prefix(fpr, py, "EndPacket")


def test_cancel_matches_oracle():
    py = CancelPacket(sequence_index=3).to_bytes()
    fpr = _read_vector("cancel_seq3.bin")
    _assert_prefix(fpr, py, "CancelPacket")


if __name__ == "__main__":
    import sys
    import traceback

    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    failures = 0
    for t in tests:
        try:
            t()
            print(f"  ok  {t.__name__}")
        except Exception:
            failures += 1
            print(f"  FAIL {t.__name__}")
            traceback.print_exc()
    print(f"\n{len(tests) - failures}/{len(tests)} oracle tests passed")
    sys.exit(1 if failures else 0)
