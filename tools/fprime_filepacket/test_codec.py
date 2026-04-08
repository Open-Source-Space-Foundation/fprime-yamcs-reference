"""Hand-rolled wire-format tests for the FilePacket codec.

These vectors are computed by hand from the published wire format. They
serve as a sanity check on our reading of FilePacket.hpp; they are NOT a
substitute for the cross-implementation oracle test (L1 in the testing
strategy), which would generate goldens from F´'s own C++ encoder. That
test belongs in a future commit once the build is wired up.

Run with:  python -m pytest tools/fprime_filepacket/test_codec.py -v
Or:        python tools/fprime_filepacket/test_codec.py
"""

from __future__ import annotations

from fprime_filepacket import (
    CancelPacket,
    DataPacket,
    EndPacket,
    FW_PACKET_FILE,
    Header,
    PacketType,
    StartPacket,
    cfdp_modular_checksum,
    decode_compacket,
    encode_compacket,
)


# ---------------------------------------------------------------------------
# Header
# ---------------------------------------------------------------------------


def test_header_round_trip():
    h = Header(PacketType.T_DATA, 0x01020304)
    raw = h.to_bytes()
    assert raw == bytes([0x01, 0x01, 0x02, 0x03, 0x04])
    assert Header.from_bytes(raw) == h


def test_header_size():
    assert Header.HEADER_SIZE == 5


# ---------------------------------------------------------------------------
# StartPacket
# ---------------------------------------------------------------------------


def test_start_packet_known_vector():
    p = StartPacket(
        sequence_index=0,
        file_size=100,
        source_path="a",
        destination_path="b",
    )
    expected = bytes(
        [
            0x00,  # T_START
            0x00, 0x00, 0x00, 0x00,  # seq=0
            0x00, 0x00, 0x00, 0x64,  # file_size=100
            0x01, ord("a"),           # src "a"
            0x01, ord("b"),           # dst "b"
        ]
    )
    assert p.to_bytes() == expected
    assert StartPacket.from_bytes(expected) == p


def test_start_packet_long_paths_round_trip():
    p = StartPacket(
        sequence_index=42,
        file_size=2**31,
        source_path="/sd0/some/long/path/with/dirs.bin",
        destination_path="downlinks/x.bin",
    )
    assert StartPacket.from_bytes(p.to_bytes()) == p


def test_start_packet_max_path_length():
    long = "x" * 255
    p = StartPacket(
        sequence_index=1, file_size=1, source_path=long, destination_path="y"
    )
    assert StartPacket.from_bytes(p.to_bytes()) == p


def test_start_packet_path_too_long_rejected():
    too_long = "x" * 256
    p = StartPacket(
        sequence_index=1, file_size=1, source_path=too_long, destination_path="y"
    )
    try:
        p.to_bytes()
    except ValueError:
        return
    raise AssertionError("Expected ValueError for 256-byte path")


# ---------------------------------------------------------------------------
# DataPacket
# ---------------------------------------------------------------------------


def test_data_packet_known_vector():
    p = DataPacket(sequence_index=1, byte_offset=0, data=b"hello")
    expected = bytes(
        [
            0x01,  # T_DATA
            0x00, 0x00, 0x00, 0x01,  # seq=1
            0x00, 0x00, 0x00, 0x00,  # byte_offset=0
            0x00, 0x05,              # data_size=5
        ]
    ) + b"hello"
    assert p.to_bytes() == expected
    assert DataPacket.from_bytes(expected) == p


def test_data_packet_empty_payload():
    p = DataPacket(sequence_index=0, byte_offset=0, data=b"")
    assert DataPacket.from_bytes(p.to_bytes()) == p


def test_data_packet_large_payload():
    payload = bytes(range(256)) * 16  # 4096 bytes
    p = DataPacket(sequence_index=99, byte_offset=4096, data=payload)
    assert DataPacket.from_bytes(p.to_bytes()) == p


# ---------------------------------------------------------------------------
# EndPacket
# ---------------------------------------------------------------------------


def test_end_packet_known_vector():
    p = EndPacket(sequence_index=2, checksum=0xDEADBEEF)
    expected = bytes(
        [
            0x02,  # T_END
            0x00, 0x00, 0x00, 0x02,  # seq=2
            0xDE, 0xAD, 0xBE, 0xEF,  # checksum
        ]
    )
    assert p.to_bytes() == expected
    assert EndPacket.from_bytes(expected) == p


# ---------------------------------------------------------------------------
# CancelPacket
# ---------------------------------------------------------------------------


def test_cancel_packet_known_vector():
    p = CancelPacket(sequence_index=3)
    expected = bytes([0x03, 0x00, 0x00, 0x00, 0x03])
    assert p.to_bytes() == expected
    assert CancelPacket.from_bytes(expected) == p


# ---------------------------------------------------------------------------
# CFDP modular checksum
# ---------------------------------------------------------------------------


def test_cfdp_checksum_empty():
    assert cfdp_modular_checksum(b"") == 0


def test_cfdp_checksum_single_aligned_word():
    # A single aligned word: bytes are placed MSB-first.
    # 0x12345678 → m_value = 0x12345678
    assert cfdp_modular_checksum(bytes([0x12, 0x34, 0x56, 0x78])) == 0x12345678


def test_cfdp_checksum_known_vector_hello():
    # bytes "hello" = 0x68 0x65 0x6c 0x6c 0x6f
    # word 0: 0x68656c6c
    # word 1: 0x6f000000  (last byte zero-padded to 4-byte boundary)
    # sum:    0xd7656c6c
    assert cfdp_modular_checksum(b"hello") == 0xD7656C6C


def test_cfdp_checksum_two_aligned_words():
    data = bytes([0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02])
    assert cfdp_modular_checksum(data) == 0x00000003


def test_cfdp_checksum_overflow_wraps_mod_2_32():
    # Two words that overflow 32 bits when summed.
    data = bytes([0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x02])
    assert cfdp_modular_checksum(data) == 0x00000001


# ---------------------------------------------------------------------------
# ComPacket descriptor wrapping
# ---------------------------------------------------------------------------


def test_compacket_wrap_unwrap_round_trip():
    inner = StartPacket(
        sequence_index=7, file_size=1024, source_path="src", destination_path="dst"
    )
    wrapped = encode_compacket(inner)
    # First two bytes are the FW_PACKET_FILE descriptor in big-endian U16.
    assert wrapped[:2] == bytes([0x00, FW_PACKET_FILE])
    assert decode_compacket(wrapped) == inner


def test_compacket_dispatches_by_type():
    samples = [
        StartPacket(0, 10, "a", "b"),
        DataPacket(1, 0, b"abc"),
        EndPacket(2, 0xCAFEBABE),
        CancelPacket(3),
    ]
    for s in samples:
        assert decode_compacket(encode_compacket(s)) == s


def test_compacket_rejects_wrong_descriptor():
    bad = bytes([0x00, 0x01]) + StartPacket(0, 1, "a", "b").to_bytes()
    try:
        decode_compacket(bad)
    except ValueError:
        return
    raise AssertionError("Expected ValueError for non-file descriptor")


# ---------------------------------------------------------------------------
# Test runner (works without pytest)
# ---------------------------------------------------------------------------


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
    print(f"\n{len(tests) - failures}/{len(tests)} tests passed")
    sys.exit(1 if failures else 0)
