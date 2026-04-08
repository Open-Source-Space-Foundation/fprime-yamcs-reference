"""L3 fake spacecraft: send Fw::FilePacket downlinks at YAMCS via UDP.

Sits where a real F´ binary would, on the YAMCS UDP_TM_IN port (default
50000). Encodes a known file as a sequence of Fw::FilePacket Start +
Data + End packets via the validated Python codec at
tools/fprime_filepacket/, wraps each in a CCSDS space packet on the
file APID (3), wraps each space packet in a CCSDS TM transfer frame
matching this deployment's configuration (spacecraftId 68, vcId 1,
1024-byte fixed frames, CRC16), and pushes the frames into UDP.

Then polls the YAMCS bucket API until the file appears (or times out).

If everything works, the run prints `OK roundtrip <bytes> bytes` and
the named file shows up in the YAMCS web UI under
Buckets > fprimeFilesIn.

Run with YAMCS already up (the FprimeFilePacketService must be loaded):

    PYTHONPATH=tools python3 tools/l3_fake_spacecraft.py
"""

from __future__ import annotations

import hashlib
import socket
import sys
import time
import urllib.request

from spacepackets.ccsds import PacketType, SpacePacket, SpacePacketHeader
from spacepackets.ccsds.tm_frame import (
    MasterChannelId,
    TmFramePrimaryHeader,
    TmTransferFrame,
    TransferFrameDataFieldStatus,
)
from fastcrc import crc16

from fprime_filepacket import (
    DataPacket,
    EndPacket,
    StartPacket,
    cfdp_modular_checksum,
    encode_compacket,
)


# ----------------------------------------------------------------------
# Deployment-specific constants — must agree with
# FprimeYamcsReference/yamcs/src/main/yamcs/etc/yamcs.fprime-project.yaml
# and lib/fprime/default/config/ComCfg.fpp
# ----------------------------------------------------------------------

YAMCS_HOST = "127.0.0.1"
YAMCS_TM_PORT = 50000
YAMCS_HTTP = "http://localhost:8090"

SPACECRAFT_ID = 68
VC_ID = 1
TM_FRAME_LENGTH = 1024
FILE_APID = 3
BUCKET = "fprimeFilesIn"


# ----------------------------------------------------------------------
# Frame builder
# ----------------------------------------------------------------------


def build_tm_frame(payload_packet_bytes: bytes, vc_frame_count: int) -> bytes:
    """Wrap a single CCSDS space packet in a TM transfer frame.

    Layout: 6-byte primary header + payload + idle padding (0xCA) +
    2-byte CRC16, total = TM_FRAME_LENGTH bytes.
    """
    # Build primary header. first_header_pointer = 0 means the first
    # packet starts at the very beginning of the data field, which is
    # what YAMCS's UdpTmFrameLink with PACKET service expects.
    status = TransferFrameDataFieldStatus(
        secondary_header_flag=False,
        sync_flag=False,
        packet_order_flag=False,
        segment_len_id=3,  # SPP_NO_SEGMENTATION (no source-packet segmentation)
        first_header_pointer=0,
    )
    header = TmFramePrimaryHeader(
        master_channel_id=MasterChannelId(
            transfer_frame_version=0,
            spacecraft_id=SPACECRAFT_ID,
        ),
        vc_id=VC_ID,
        ocf_flag=False,
        master_ch_frame_count=vc_frame_count & 0xFF,
        vc_frame_count=vc_frame_count & 0xFF,
        frame_datafield_status=status,
    )

    # Data field: the user packet, then idle padding to reach the right
    # length minus the 2-byte CRC trailer.
    data_field_size = TM_FRAME_LENGTH - 6 - 2
    if len(payload_packet_bytes) > data_field_size:
        raise ValueError(
            f"Packet ({len(payload_packet_bytes)} B) does not fit "
            f"in TM data field ({data_field_size} B)"
        )
    data_field = payload_packet_bytes + b"\xca" * (
        data_field_size - len(payload_packet_bytes)
    )

    # Build a placeholder frame so we can serialize and CRC it.
    frame = TmTransferFrame(
        length=TM_FRAME_LENGTH,
        primary_header=header,
        secondary_header=None,
        data_field=data_field,
        op_ctrl_field=None,
        frame_error_control=b"\x00\x00",  # placeholder, replaced below
    )

    # spacepackets serializes header + data + the placeholder CRC. We
    # need to compute the real CRC over header + data and patch it in.
    raw = bytearray(frame.pack())
    crc = crc16.ibm_3740(bytes(raw[:-2]))
    raw[-2:] = crc.to_bytes(2, "big")
    return bytes(raw)


def build_space_packet(payload: bytes, seq_count: int) -> bytes:
    """Wrap a Fw::FilePacket-with-ComPacket-descriptor blob in a CCSDS
    space packet on the file APID."""
    header = SpacePacketHeader(
        packet_type=PacketType.TM,
        apid=FILE_APID,
        seq_count=seq_count & 0x3FFF,
        data_len=len(payload) - 1,  # CCSDS convention: length-of-data minus 1
    )
    sp = SpacePacket(sp_header=header, sec_header=None, user_data=payload)
    return sp.pack()


# ----------------------------------------------------------------------
# Test driver
# ----------------------------------------------------------------------


def send_file(sock, source_path: str, dest_path: str, content: bytes,
              packet_size: int = 256) -> None:
    """Send `content` as a Start + Data×N + End sequence over UDP."""
    seq = 0
    fc = 0  # vc frame count

    # Start
    start = StartPacket(
        sequence_index=seq,
        file_size=len(content),
        source_path=source_path,
        destination_path=dest_path,
    )
    sp_bytes = build_space_packet(encode_compacket(start), fc)
    sock.sendto(build_tm_frame(sp_bytes, fc), (YAMCS_HOST, YAMCS_TM_PORT))
    fc += 1

    # Data×N
    for offset in range(0, len(content), packet_size):
        chunk = content[offset : offset + packet_size]
        seq += 1
        data = DataPacket(sequence_index=seq, byte_offset=offset, data=chunk)
        sp_bytes = build_space_packet(encode_compacket(data), fc)
        sock.sendto(build_tm_frame(sp_bytes, fc), (YAMCS_HOST, YAMCS_TM_PORT))
        fc += 1
        # Brief pause so YAMCS frame parser doesn't get overwhelmed.
        time.sleep(0.01)

    # End
    seq += 1
    end = EndPacket(sequence_index=seq, checksum=cfdp_modular_checksum(content))
    sp_bytes = build_space_packet(encode_compacket(end), fc)
    sock.sendto(build_tm_frame(sp_bytes, fc), (YAMCS_HOST, YAMCS_TM_PORT))


def poll_bucket(object_name: str, expected_sha: str, timeout_s: float = 10.0) -> bool:
    """Poll the YAMCS HTTP API until the file appears in the bucket and
    its sha256 matches `expected_sha`."""
    deadline = time.time() + timeout_s
    url = f"{YAMCS_HTTP}/api/buckets/_global/{BUCKET}/objects/{object_name}"
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=2) as resp:
                bytes_received = resp.read()
            sha = hashlib.sha256(bytes_received).hexdigest()
            if sha == expected_sha:
                print(f"OK roundtrip {len(bytes_received)} bytes; sha256={sha}")
                return True
            print(
                f"  bucket has {len(bytes_received)} bytes but sha mismatch:\n"
                f"    expected: {expected_sha}\n    got:      {sha}"
            )
            return False
        except urllib.error.HTTPError as e:
            if e.code != 404:
                print(f"  HTTP error polling bucket: {e}")
                return False
        except Exception as e:
            print(f"  poll error: {e}")
        time.sleep(0.25)
    print(f"  TIMEOUT after {timeout_s}s — file never appeared in bucket")
    return False


def main() -> int:
    # A small, recognizable test payload.
    content = b"hello, fprime+yamcs file transfer\n" * 8  # ~272 bytes
    src_path = "/sd0/test.txt"
    dst_path = "test.txt"
    expected_sha = hashlib.sha256(content).hexdigest()

    print(f"Sending {len(content)} bytes to {YAMCS_HOST}:{YAMCS_TM_PORT}")
    print(f"  src={src_path} dst={dst_path}")
    print(f"  sha256={expected_sha}")
    print(f"  cfdp_checksum=0x{cfdp_modular_checksum(content):08x}")

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        send_file(sock, src_path, dst_path, content)
    finally:
        sock.close()

    print("Frames sent. Polling YAMCS bucket...")
    return 0 if poll_bucket(dst_path, expected_sha) else 1


if __name__ == "__main__":
    sys.exit(main())
