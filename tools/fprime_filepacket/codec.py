"""Wire-format codec for Fw::FilePacket and its ComPacket-descriptor wrapper.

All multi-byte integers are big-endian (F´ SerialBuffer default).

Layout summary
--------------

Outer wrapper (the bytes that go inside a CCSDS space packet payload when
the file APID is in use):

    [U16 BE: ComPacket descriptor = FW_PACKET_FILE = 0x0003]
    [Fw::FilePacket bytes...]

Inner Fw::FilePacket layouts:

    Header     (5 bytes)  : U8 type | U32 BE sequenceIndex
    PathName   (1+N bytes): U8 length | chars (no null terminator)

    StartPacket  : Header | U32 BE fileSize | PathName srcPath | PathName destPath
    DataPacket   : Header | U32 BE byteOffset | U16 BE dataSize | bytes[dataSize]
    EndPacket    : Header | U32 BE checksum  (CFDP modular checksum of file)
    CancelPacket : Header
"""

from __future__ import annotations

import struct
from dataclasses import dataclass
from enum import IntEnum
from typing import Tuple, Union

# F´ ComCfg::Apid value for file packets. Verified in
# lib/fprime/default/config/ComCfg.fpp:27.
FW_PACKET_FILE: int = 0x0003


class PacketType(IntEnum):
    """Fw::FilePacket::Type enum, copied from FilePacket.hpp:40."""

    T_START = 0
    T_DATA = 1
    T_END = 2
    T_CANCEL = 3
    T_NONE = 255


# ---------------------------------------------------------------------------
# Low-level helpers
# ---------------------------------------------------------------------------


def cfdp_modular_checksum(data: bytes) -> int:
    """The CFDP modular file checksum, as implemented by F´.

    Equivalent to summing the file as a sequence of 4-byte big-endian words,
    zero-padded to a 4-byte boundary, modulo 2^32. Translated directly from
    lib/fprime/CFDP/Checksum/Checksum.cpp.
    """
    csum = 0
    for i, b in enumerate(data):
        csum = (csum + (b << (8 * (3 - (i % 4))))) & 0xFFFFFFFF
    return csum


def _encode_pathname(name: str) -> bytes:
    encoded = name.encode("ascii")
    if len(encoded) > 255:
        raise ValueError(f"PathName too long: {len(encoded)} bytes (max 255)")
    return struct.pack(">B", len(encoded)) + encoded


def _decode_pathname(buf: bytes, offset: int) -> Tuple[str, int]:
    length = buf[offset]
    start = offset + 1
    end = start + length
    return buf[start:end].decode("ascii"), end


# ---------------------------------------------------------------------------
# Header
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Header:
    type: PacketType
    sequence_index: int

    HEADER_SIZE: int = 5

    def to_bytes(self) -> bytes:
        return struct.pack(">BI", int(self.type), self.sequence_index)

    @classmethod
    def from_bytes(cls, buf: bytes, offset: int = 0) -> "Header":
        type_byte, seq = struct.unpack_from(">BI", buf, offset)
        return cls(type=PacketType(type_byte), sequence_index=seq)


# ---------------------------------------------------------------------------
# Concrete packet variants
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class StartPacket:
    sequence_index: int
    file_size: int
    source_path: str
    destination_path: str

    def to_bytes(self) -> bytes:
        header = Header(PacketType.T_START, self.sequence_index).to_bytes()
        return (
            header
            + struct.pack(">I", self.file_size)
            + _encode_pathname(self.source_path)
            + _encode_pathname(self.destination_path)
        )

    @classmethod
    def from_bytes(cls, buf: bytes) -> "StartPacket":
        h = Header.from_bytes(buf)
        if h.type != PacketType.T_START:
            raise ValueError(f"Expected T_START, got {h.type.name}")
        offset = Header.HEADER_SIZE
        (file_size,) = struct.unpack_from(">I", buf, offset)
        offset += 4
        src, offset = _decode_pathname(buf, offset)
        dst, _ = _decode_pathname(buf, offset)
        return cls(
            sequence_index=h.sequence_index,
            file_size=file_size,
            source_path=src,
            destination_path=dst,
        )


@dataclass(frozen=True)
class DataPacket:
    sequence_index: int
    byte_offset: int
    data: bytes

    def to_bytes(self) -> bytes:
        if len(self.data) > 0xFFFF:
            raise ValueError(
                f"DataPacket payload too large: {len(self.data)} (max 65535)"
            )
        header = Header(PacketType.T_DATA, self.sequence_index).to_bytes()
        return (
            header
            + struct.pack(">IH", self.byte_offset, len(self.data))
            + self.data
        )

    @classmethod
    def from_bytes(cls, buf: bytes) -> "DataPacket":
        h = Header.from_bytes(buf)
        if h.type != PacketType.T_DATA:
            raise ValueError(f"Expected T_DATA, got {h.type.name}")
        offset = Header.HEADER_SIZE
        byte_offset, data_size = struct.unpack_from(">IH", buf, offset)
        offset += 6
        return cls(
            sequence_index=h.sequence_index,
            byte_offset=byte_offset,
            data=bytes(buf[offset : offset + data_size]),
        )


@dataclass(frozen=True)
class EndPacket:
    sequence_index: int
    checksum: int

    def to_bytes(self) -> bytes:
        header = Header(PacketType.T_END, self.sequence_index).to_bytes()
        return header + struct.pack(">I", self.checksum)

    @classmethod
    def from_bytes(cls, buf: bytes) -> "EndPacket":
        h = Header.from_bytes(buf)
        if h.type != PacketType.T_END:
            raise ValueError(f"Expected T_END, got {h.type.name}")
        (checksum,) = struct.unpack_from(">I", buf, Header.HEADER_SIZE)
        return cls(sequence_index=h.sequence_index, checksum=checksum)


@dataclass(frozen=True)
class CancelPacket:
    sequence_index: int

    def to_bytes(self) -> bytes:
        return Header(PacketType.T_CANCEL, self.sequence_index).to_bytes()

    @classmethod
    def from_bytes(cls, buf: bytes) -> "CancelPacket":
        h = Header.from_bytes(buf)
        if h.type != PacketType.T_CANCEL:
            raise ValueError(f"Expected T_CANCEL, got {h.type.name}")
        return cls(sequence_index=h.sequence_index)


FilePacket = Union[StartPacket, DataPacket, EndPacket, CancelPacket]


# ---------------------------------------------------------------------------
# ComPacket-descriptor wrapper
# ---------------------------------------------------------------------------


def encode_compacket(packet: FilePacket) -> bytes:
    """Wrap a FilePacket with the F´ ComPacket descriptor prefix.

    The result is the byte sequence that goes inside a CCSDS space packet
    payload on the file APID, matching what FileDownlink.cpp:357-359 emits.
    """
    return struct.pack(">H", FW_PACKET_FILE) + packet.to_bytes()


def decode_compacket(buf: bytes) -> FilePacket:
    """Strip the ComPacket descriptor prefix and decode the contained packet.

    Raises ValueError if the descriptor does not match FW_PACKET_FILE or the
    inner packet type is unrecognized.
    """
    if len(buf) < 2 + Header.HEADER_SIZE:
        raise ValueError(f"Buffer too short: {len(buf)} bytes")
    (descriptor,) = struct.unpack_from(">H", buf, 0)
    if descriptor != FW_PACKET_FILE:
        raise ValueError(
            f"Unexpected ComPacket descriptor: 0x{descriptor:04x} "
            f"(expected 0x{FW_PACKET_FILE:04x})"
        )
    inner = buf[2:]
    type_byte = inner[0]
    if type_byte == PacketType.T_START:
        return StartPacket.from_bytes(inner)
    if type_byte == PacketType.T_DATA:
        return DataPacket.from_bytes(inner)
    if type_byte == PacketType.T_END:
        return EndPacket.from_bytes(inner)
    if type_byte == PacketType.T_CANCEL:
        return CancelPacket.from_bytes(inner)
    raise ValueError(f"Unknown FilePacket type: {type_byte}")
