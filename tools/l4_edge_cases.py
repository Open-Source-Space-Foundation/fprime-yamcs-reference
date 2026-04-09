"""L4 edge-case test suite — round-trip files through the real F´ binary.

Exercises several boundary conditions against the running YAMCS +
FprimeFilePacketService + F´ binary stack, using only YAMCS's native
File Transfer REST API. Each case:

  1. POSTs a known byte payload into the YAMCS source bucket
  2. Creates an UPLOAD transfer — sends the file to F´
  3. Waits for the upload to complete
  4. Creates a DOWNLOAD transfer — pulls the same file back
  5. Waits for the download to complete
  6. GETs the downloaded bytes from the bucket
  7. Diffs against the original; pass iff sha256 matches

This is stricter than the L3 harness tests because both directions of
our Java service, the TC/TM links, and F´'s file IO are all on the
path. One failure in any layer fails the case.

Prerequisites:
- YAMCS running on http://localhost:8090
- F´ binary running with -a 127.0.0.1 -p 50000
- FprimeFilePacketService registered and subscribed

Run:

    python3 tools/l4_edge_cases.py
"""

from __future__ import annotations

import hashlib
import json
import sys
import time
import urllib.error
import urllib.request

YAMCS = "http://localhost:8090"
INSTANCE = "fprime-project"
SERVICE = "FprimeFilePacketService"
BUCKET = "fprimeFilesIn"
TRANSFER_TIMEOUT_S = 20  # wall-clock deadline for each transfer to finish


# ----------------------------------------------------------------------
# REST helpers
# ----------------------------------------------------------------------


def _req(method, path, *, json_body=None, data=None, content_type=None):
    url = YAMCS + path
    if json_body is not None:
        data = json.dumps(json_body).encode()
        content_type = "application/json"
    headers = {}
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def put_bucket_object(name, content):
    code, _ = _req("POST",
                   f"/api/buckets/_global/{BUCKET}/objects/{name}",
                   data=content, content_type="application/octet-stream")
    if code != 200:
        raise RuntimeError(f"bucket upload failed with HTTP {code}")


def get_bucket_object(name):
    code, body = _req("GET", f"/api/buckets/_global/{BUCKET}/objects/{name}")
    if code != 200:
        raise RuntimeError(f"bucket fetch failed with HTTP {code}")
    return body


def delete_bucket_object(name):
    _req("DELETE", f"/api/buckets/_global/{BUCKET}/objects/{name}")


def create_transfer(direction, *, object_name, remote_path):
    """Create an UPLOAD or DOWNLOAD transfer via the native FileTransfer API."""
    body = {
        "direction": direction,
        "bucket": BUCKET,
        "objectName": object_name,
        "remotePath": remote_path,
        "source": "ground" if direction == "UPLOAD" else "spacecraft",
        "destination": "spacecraft" if direction == "UPLOAD" else "ground",
    }
    code, payload = _req(
        "POST",
        f"/api/filetransfer/{INSTANCE}/{SERVICE}/transfers",
        json_body=body,
    )
    if code != 200:
        raise RuntimeError(f"createTransfer failed HTTP {code}: {payload.decode()}")
    return json.loads(payload)


def get_transfer(transfer_id):
    code, payload = _req(
        "GET",
        f"/api/filetransfer/{INSTANCE}/{SERVICE}/transfers/{transfer_id}",
    )
    if code != 200:
        raise RuntimeError(f"getTransfer failed HTTP {code}")
    return json.loads(payload)


def wait_for_terminal(transfer_id, timeout_s=TRANSFER_TIMEOUT_S):
    """Poll a transfer until it reaches COMPLETED/FAILED/CANCELLED."""
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        info = get_transfer(transfer_id)
        state = info.get("state")
        if state in ("COMPLETED", "FAILED", "CANCELLED"):
            return info
        time.sleep(0.1)
    raise TimeoutError(
        f"transfer {transfer_id} did not reach terminal state in {timeout_s}s")


# ----------------------------------------------------------------------
# Case runner
# ----------------------------------------------------------------------


def round_trip(name, content, skip_download_reason=None):
    """
    Upload `content` to F´, then (unless skipped) download it back and
    check the bytes match. Returns (passed, note).

    If `skip_download_reason` is set, only the upload is verified —
    useful for cases where a known F´ limitation blocks the download
    half (e.g. F´'s FileDownlink refuses zero-byte files).
    """
    src_obj = f"edge_src_{name}"
    dst_obj = f"edge_dst_{name}"
    remote = f"edge_{name}.bin"
    expected_sha = hashlib.sha256(content).hexdigest()

    try:
        # Seed the source bucket.
        put_bucket_object(src_obj, content)

        # Upload to F´.
        up = create_transfer("UPLOAD", object_name=src_obj, remote_path=remote)
        up = wait_for_terminal(up["id"])
        if up["state"] != "COMPLETED":
            return False, f"upload {up['state']}: {up.get('failureReason','?')}"
        if int(up.get("sizeTransferred", "0")) != len(content):
            return False, (
                f"upload byte count mismatch: "
                f"{up.get('sizeTransferred')} != {len(content)}")

        if skip_download_reason:
            return True, (
                f"{len(content)} bytes uploaded; download skipped "
                f"({skip_download_reason})")

        # Download back from F´.
        dn = create_transfer("DOWNLOAD", object_name=dst_obj, remote_path=remote)
        dn = wait_for_terminal(dn["id"])
        if dn["state"] != "COMPLETED":
            return False, f"download {dn['state']}: {dn.get('failureReason','?')}"

        # Fetch bytes back from the bucket.
        got = get_bucket_object(dst_obj)
        got_sha = hashlib.sha256(got).hexdigest()
        if got_sha != expected_sha:
            return False, (
                f"sha mismatch: expected {expected_sha[:12]}... "
                f"got {got_sha[:12]}... ({len(got)} bytes)")
        return True, f"{len(content)} bytes round-tripped"

    finally:
        # Best-effort cleanup so test reruns are idempotent.
        try:
            delete_bucket_object(src_obj)
        except Exception:
            pass
        try:
            delete_bucket_object(dst_obj)
        except Exception:
            pass


# ----------------------------------------------------------------------
# Test cases
#
# CHUNK_SIZE mirrors FprimeFilePacketService's uplinkChunkSize default.
# ----------------------------------------------------------------------

CHUNK_SIZE = 128

# Each case is (name, content, skip_download_reason-or-None).
# skip_download_reason documents known F´ limitations that prevent the
# downlink half of a round-trip from completing — the upload is still
# verified but the download step is not attempted.
CASES = [
    # Empty file: F´ Svc::FileDownlink refuses to downlink zero-byte
    # files with 'DownlinkPartialFail: Offset 0 greater than or equal
    # to source filesize 0'. The uplink half works fine — F´ accepts
    # Start + End with no intervening Data packets and writes a real
    # 0-byte file to disk. The download-side limitation is upstream F´
    # behavior, not a bug in our service.
    ("empty",             b"",                                  "F´ FileDownlink rejects zero-byte source files"),
    # Boundary conditions for the Fw::FilePacket state machine.
    ("single_byte",       b"x",                                 None),
    ("exact_1_chunk",     b"A" * CHUNK_SIZE,                    None),
    ("chunk_plus_1",      b"B" * (CHUNK_SIZE + 1),              None),
    ("exact_3_chunks",    b"C" * (CHUNK_SIZE * 3),              None),
    ("multi_chunk_small", bytes(range(256)) * 4,                None),  # 1024 B, 8 chunks
    ("multi_chunk_big",   bytes(range(256)) * 16,               None),  # 4096 B, 32 chunks
    # Back-to-back distinct transfers ensure the service resets state
    # between transfers and doesn't cross-contaminate them.
    ("sequential_a",      b"first back-to-back file\n" * 10,    None),
    ("sequential_b",      b"second back-to-back file\n" * 10,   None),
]


def main():
    print(f"L4 edge-case suite — {len(CASES)} cases, YAMCS at {YAMCS}")
    print("=" * 60)

    passed = 0
    failed = 0

    for entry in CASES:
        name, content, skip_reason = entry
        print(f"  {name:<22}", end=" ", flush=True)
        t0 = time.time()
        try:
            ok, note = round_trip(name, content, skip_download_reason=skip_reason)
        except Exception as e:
            ok, note = False, f"error: {e}"
        dt = (time.time() - t0) * 1000
        tag = "PASS" if ok else "FAIL"
        print(f"{tag}  ({dt:5.0f} ms)  {note}")
        if ok:
            passed += 1
        else:
            failed += 1

    print("=" * 60)
    print(f"  {passed}/{len(CASES)} passed, {failed} failed")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
