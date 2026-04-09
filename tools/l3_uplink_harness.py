"""L3 uplink harness: push a file into the F´ binary via Fw::FilePacket.

The inverse of tools/l3_fake_spacecraft.py. Instead of pretending to be
a spacecraft sending TM to YAMCS, this pretends to be a ground system
sending TC to the F´ binary — which is how file uplink works.

Pipeline:
  Python codec builds Fw::FilePacket {Start, Data×N, End} packets
    -> wraps each in a CCSDS space packet on APID 3 (FW_PACKET_FILE)
    -> wraps each space packet in a CCSDS TC transfer frame
       (Type-BD, spacecraftId 68, vcId 1, CRC16-CCITT trailer)
    -> UDP sendto(127.0.0.1, 50001)
  F´ binary's comDriver receives on 50001
    -> FrameAccumulator -> TcDeframer -> SpacePacketDeframer
    -> fprimeRouter (dispatches by APID)
    -> Svc::FileUplink
    -> writes file to disk at the destination path from the Start packet

Wire format sources:
- TC frame: lib/fprime/Svc/Ccsds/TcDeframer/TcDeframer.cpp (header comment
  and dataIn_handler)
- Spacecraft ID, VC ID: lib/fprime/default/config/ComCfg.fpp (SpacecraftId)
  and the YAMCS yaml
- CCSDS space packet: spacepackets library
- Fw::FilePacket: tools/fprime_filepacket/ (already L1-validated)

Prerequisites:
- F´ binary running with -a 127.0.0.1 -p 50000 (so it receives on 50001)
- The Python venv activated
- PYTHONPATH=tools (for the fprime_filepacket import)

Run:
    source fprime-venv/bin/activate
    PYTHONPATH=tools python3 tools/l3_uplink_harness.py
"""

from __future__ import annotations

import hashlib
import socket
import sys
import time

from spacepackets.ccsds import PacketType, SpacePacket, SpacePacketHeader
from fastcrc import crc16

from fprime_filepacket import (
    DataPacket,
    EndPacket,
    StartPacket,
    cfdp_modular_checksum,
    encode_compacket,
)


# ----------------------------------------------------------------------
# Deployment constants — must agree with F´ ComCfg.fpp and YAMCS yaml
# ----------------------------------------------------------------------

FPRIME_HOST = "127.0.0.1"
FPRIME_TC_PORT = 50001

SPACECRAFT_ID = 68  # 0x044; 10-bit field
VC_ID = 1  # 6-bit field
FILE_APID = 3

TC_HEADER_LEN = 5
TC_TRAILER_LEN = 2  # CRC16

# Small frames for uplink — keep each TC frame carrying at most one
# space packet. F´ TcDeframer accepts frames up to a few hundred bytes
# comfortably; no need to saturate.
UPLINK_CHUNK_SIZE = 128


# ----------------------------------------------------------------------
# TC frame builder
# ----------------------------------------------------------------------


def build_tc_frame(data_field: bytes) -> bytes:
    """Wrap a data field (typically one CCSDS space packet) in a
    CCSDS TC Type-BD transfer frame with CRC16 trailer.

    Format (from TcDeframer.cpp:36-49):
        2b  version = 00
        1b  bypass flag = 1 (Type-B)
        1b  ctrl cmd flag = 0 (Type-D)
        2b  spare = 00
        10b spacecraft id
        6b  virtual channel id
        10b frame length (total bytes - 1)
        8b  frame sequence number (unused for Type-B, use 0)
        variable data field
        16b FECF = CRC16-CCITT over [header + data field]
    """
    total_len = TC_HEADER_LEN + len(data_field) + TC_TRAILER_LEN
    length_field = total_len - 1  # "frame length is bytes minus 1"

    header = bytearray(TC_HEADER_LEN)
    # Word 0: [ver=00 | byp=1 | ctrl=0 | spare=00 | scid(10)]
    word0 = (0 << 14) | (1 << 13) | (0 << 12) | (0 << 10) | (SPACECRAFT_ID & 0x03FF)
    header[0] = (word0 >> 8) & 0xFF
    header[1] = word0 & 0xFF
    # Word 1: [vcid(6) | length(10)]
    word1 = ((VC_ID & 0x3F) << 10) | (length_field & 0x03FF)
    header[2] = (word1 >> 8) & 0xFF
    header[3] = word1 & 0xFF
    # Word 2: seq number (unused, 0)
    header[4] = 0

    header_and_data = bytes(header) + data_field
    # fastcrc.crc16.ibm_3740 is CRC-16-CCITT-FALSE (poly 0x1021, init 0xFFFF)
    # which matches the F´ Svc::Ccsds::Utils::CRC16 implementation.
    fecf = crc16.ibm_3740(header_and_data)
    return header_and_data + fecf.to_bytes(2, "big")


# ----------------------------------------------------------------------
# CCSDS space packet builder (same as L3 downlink harness)
# ----------------------------------------------------------------------


def build_space_packet(payload: bytes, seq_count: int) -> bytes:
    header = SpacePacketHeader(
        packet_type=PacketType.TC,
        apid=FILE_APID,
        seq_count=seq_count & 0x3FFF,
        data_len=len(payload) - 1,
    )
    return SpacePacket(sp_header=header, sec_header=None, user_data=payload).pack()


# ----------------------------------------------------------------------
# File uploader
# ----------------------------------------------------------------------


def upload_file(sock: socket.socket, source_path: str, dest_path: str,
                content: bytes) -> None:
    seq = 0
    ssp = 0  # space packet sequence count

    def send_packet(file_packet_bytes: bytes) -> None:
        nonlocal ssp
        sp = build_space_packet(file_packet_bytes, ssp)
        frame = build_tc_frame(sp)
        sock.sendto(frame, (FPRIME_HOST, FPRIME_TC_PORT))
        ssp += 1
        # Give F´ a moment to drain each frame; the frame accumulator
        # is a small buffer and back-to-back UDP sends can otherwise be
        # dropped under load.
        time.sleep(0.02)

    # Start
    start = StartPacket(
        sequence_index=seq,
        file_size=len(content),
        source_path=source_path,
        destination_path=dest_path,
    )
    send_packet(encode_compacket(start))

    # Data×N
    for offset in range(0, len(content), UPLINK_CHUNK_SIZE):
        chunk = content[offset : offset + UPLINK_CHUNK_SIZE]
        seq += 1
        data = DataPacket(sequence_index=seq, byte_offset=offset, data=chunk)
        send_packet(encode_compacket(data))

    # End
    seq += 1
    end = EndPacket(sequence_index=seq, checksum=cfdp_modular_checksum(content))
    send_packet(encode_compacket(end))


# ----------------------------------------------------------------------
# Driver
# ----------------------------------------------------------------------


def main() -> int:
    content = b"uplinked from L3 harness at " + time.strftime("%Y-%m-%d %H:%M:%S").encode() + b"\n"
    content = content * 4  # a couple hundred bytes
    src_path = "uplink_source"  # informational only
    dst_path = "uplinked_test.txt"

    print(f"Uplinking {len(content)} bytes to F´ at {FPRIME_HOST}:{FPRIME_TC_PORT}")
    print(f"  dest path:  {dst_path}  (relative to F´'s cwd)")
    print(f"  sha256:     {hashlib.sha256(content).hexdigest()}")
    print(f"  cfdp cksum: 0x{cfdp_modular_checksum(content):08x}")

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        upload_file(sock, src_path, dst_path, content)
    finally:
        sock.close()

    print("Frames sent. Check F´'s cwd for the destination file and")
    print("tail /tmp/fprime-bin.log for FileUplink events.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
