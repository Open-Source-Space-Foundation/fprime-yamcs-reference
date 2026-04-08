"""Reference codec for F Prime Fw::FilePacket and the surrounding ComPacket
descriptor.

This package is a wire-format reference implementation in Python, intended
for two purposes:

1. Validate the design team's reading of the wire format independently of
   any C++ or Java implementation.
2. Serve as the "fake spacecraft" for L3 end-to-end tests in the testing
   strategy in docs/file-transfer-integration.md.

The format is extracted from:
- lib/fprime/Fw/FilePacket/FilePacket.hpp (struct layout)
- lib/fprime/Fw/FilePacket/Header.cpp     (serialization)
- lib/fprime/Svc/FileDownlink/FileDownlink.cpp (ComPacket descriptor prefix)
- lib/fprime/CFDP/Checksum/Checksum.cpp   (End packet checksum algorithm)

It is NOT a port of F´. It is an independent re-implementation against the
public wire format. Do not use it as flight code.
"""

from .codec import (
    PacketType,
    FW_PACKET_FILE,
    Header,
    StartPacket,
    DataPacket,
    EndPacket,
    CancelPacket,
    cfdp_modular_checksum,
    encode_compacket,
    decode_compacket,
)

__all__ = [
    "PacketType",
    "FW_PACKET_FILE",
    "Header",
    "StartPacket",
    "DataPacket",
    "EndPacket",
    "CancelPacket",
    "cfdp_modular_checksum",
    "encode_compacket",
    "decode_compacket",
]
