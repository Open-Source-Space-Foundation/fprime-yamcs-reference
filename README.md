# FprimeYamcsReference

A reference F Prime deployment integrated with YAMCS as the ground
system. YAMCS handles command/telemetry over the existing CCSDS UDP
link, and file transfer between ground and spacecraft uses F´'s
native `Fw::FilePacket` format routed through a custom YAMCS
`FileTransferService`.

Operators get a CFDP-parity experience in the YAMCS web UI:

- **Upload** files from a YAMCS bucket to the spacecraft
- **Download** files from the spacecraft into a YAMCS bucket
- **Browse** the spacecraft's filesystem as a file tree
- **Click** any file in the tree to download it

All three flows go through YAMCS's native `FileTransferService`
REST API and web UI — no custom client, no custom REST endpoint.

See [`docs/file-transfer-integration.md`](docs/file-transfer-integration.md)
for the full design, testing strategy, and rationale behind the
Option C architecture (native F´ `Fw::FilePacket` in YAMCS, no CFDP
protocol translation).

## One-time setup

1. Clone this repo and initialize the `lib/fprime` submodule:
   ```sh
   git submodule update --init --recursive lib/fprime
   ```
2. Install system prerequisites (Debian/Ubuntu):
   ```sh
   sudo apt install -y build-essential python3-venv default-jdk maven
   ```
3. Create and activate a Python virtual environment:
   ```sh
   python3 -m venv fprime-venv
   source fprime-venv/bin/activate
   ```
4. Install Python dependencies (F´ tools, `fprime-yamcs`, and
   `fprime-xtce`):
   ```sh
   pip install -r requirements.txt
   ```
5. Apply the `fprime-xtce` patch for `Fw::FilePacket` container
   support. Until [the patch lands upstream][xtce-pr], copy the two
   patched files from `dict-additions/fprime_xtce_patch/` into your
   venv:
   ```sh
   cp dict-additions/fprime_xtce_patch/primitive_types.py \
      fprime-venv/lib/python3.11/site-packages/fprime_xtce/
   cp dict-additions/fprime_xtce_patch/primitive_containers.py \
      fprime-venv/lib/python3.11/site-packages/fprime_xtce/
   ```

[xtce-pr]: https://github.com/fprime-community/fprime-xtce

## Building F´

Standard F´ flow:

```sh
source fprime-venv/bin/activate
cd FprimeYamcsReference/YamcsDeployment
fprime-util generate
fprime-util build
```

This produces the F´ binary at
`build-artifacts/Linux/FprimeYamcsReference_YamcsDeployment/bin/FprimeYamcsReference_YamcsDeployment`.

## Generating the YAMCS XTCE dictionary

Regenerate the vendored XTCE file from the F´ JSON dictionary. This
picks up the file-packet containers from the `fprime-xtce` patch:

```sh
source fprime-venv/bin/activate
fprime-to-xtce \
  -o FprimeYamcsReference/yamcs/src/main/yamcs/mdb/fprime.xtce.xml \
  build-artifacts/Linux/FprimeYamcsReference_YamcsDeployment/dict/YamcsDeploymentTopologyDictionary.json
```

Re-run this whenever the F´ command/telemetry dictionary changes.

## Running YAMCS and F´

Launch YAMCS and the F´ binary in two separate terminals. This repo
does not use the stock `fprime-yamcs` CLI — it has its own vendored
YAMCS Maven project under `FprimeYamcsReference/yamcs/` with the
custom `FprimeFilePacketService` baked in.

### Terminal 1 — YAMCS

```sh
cd FprimeYamcsReference/yamcs
source ../../fprime-venv/bin/activate
export FPRIME_DICTIONARY=$(pwd)/../../build-artifacts/Linux/FprimeYamcsReference_YamcsDeployment/dict/YamcsDeploymentTopologyDictionary.json
mvn yamcs:run -Dyamcs.directory=/tmp/yamcs-data
```

`FPRIME_DICTIONARY` is required so the `fprime-yamcs-events`
ProcessRunner subprocess can decode F´ events and publish them to
YAMCS. Without it, events don't flow and the remote file browser
won't work.

Once YAMCS is up, the web UI is at **http://localhost:8090**.

### Terminal 2 — F´ binary

```sh
build-artifacts/Linux/FprimeYamcsReference_YamcsDeployment/bin/FprimeYamcsReference_YamcsDeployment \
  -a 127.0.0.1 -p 50000
```

`-a 127.0.0.1` is required — the default `0.0.0.0` does not route
TM to the loopback interface where YAMCS is listening.

`-p 50000` sets the port for outbound TM; F´ binds TC receive on
`port + 1 = 50001`, which matches YAMCS's `UDP_TC_OUT` destination.

## Using the File Transfer UI

Open **http://localhost:8090** and click **File Transfer** in the
left navigation. `FprimeFilePacketService` appears in the service
list with capabilities `upload`, `download`, and `fileList`.

### Upload a file to the spacecraft

1. Upload a file into a YAMCS bucket via **Buckets** or the API
2. **File Transfer → Upload**
3. Pick source bucket + object, destination path, entities
4. Click **Start** — transfer appears in the active list with
   progress, transitions `RUNNING` → `COMPLETED`

### Download a specific file (path known in advance)

1. **File Transfer → Download**
2. Type the path on the spacecraft (e.g. `Main.cpp`)
3. Pick destination bucket + object name
4. Click **Start**
5. The file appears in the bucket when the transfer completes

### Browse the spacecraft's filesystem

1. **File Transfer → click `FprimeFilePacketService`**
2. The file tree shows the current directory (initially `.`)
3. Click a folder to expand it (triggers a background
   `ListDirectory` command to F´ and refreshes the view)
4. Click a file to start a download

## Testing

This repo ships multiple test layers that can run end-to-end against
the real F´ binary:

- **L1 — Python codec vs F´ C++ encoder.** Hand-rolled byte vectors
  plus a C++ oracle program that dumps golden output from F´'s own
  `Fw::FilePacket` implementation. See
  [`tools/fprime_filepacket/oracle/`](tools/fprime_filepacket/oracle/).
- **L3 — Fake spacecraft via Python.** Send and receive files via
  raw UDP using hand-built CCSDS frames. See
  [`tools/l3_fake_spacecraft.py`](tools/l3_fake_spacecraft.py) for
  downlink and
  [`tools/l3_uplink_harness.py`](tools/l3_uplink_harness.py) for
  uplink.
- **L4 — Real F´ binary end-to-end.** Procedure documented in
  [`tools/l4_real_fprime_test.md`](tools/l4_real_fprime_test.md).

The full testing strategy, including property tests and what each
layer does or doesn't catch, is in the
["Testing strategy" section of the design doc](docs/file-transfer-integration.md#testing-strategy).

## Repository layout

```
FprimeYamcsReference/
  YamcsDeployment/        F´ deployment (components, topology)
  yamcs/                  Vendored YAMCS Maven project with the
                          custom FprimeFilePacketService. This is
                          the YAMCS build that gets launched — NOT
                          the stock fprime-yamcs venv install.
    src/main/java/org/fprimeyamcs/reference/
      FprimeFilePacketService.java    main service
      FprimePacketPreprocessor.java   TM packet preprocessor
      FprimeCommandPostprocessor.java TC command postprocessor
    src/main/yamcs/etc/
      yamcs.fprime-project.yaml       YAMCS instance config

docs/file-transfer-integration.md   design doc (read this first)

dict-additions/
  fprime_xtce_patch/      patched primitive_types.py and
                          primitive_containers.py that teach
                          fprime-to-xtce about Fw::FilePacket.
                          Apply to the venv install until the
                          upstream PR lands.

tools/
  fprime_filepacket/      Python wire-format codec for Fw::FilePacket
    codec.py              Start/Data/End/Cancel encoders + CFDP checksum
    test_codec.py         19 unit tests + 4 oracle tests
    oracle/               C++ program that uses F´'s own encoder
                          to produce golden byte vectors
  l3_fake_spacecraft.py   send canned files at YAMCS as TM frames
  l3_uplink_harness.py    send canned files at F´ as TC frames
  l4_real_fprime_test.md  end-to-end procedure with the real binary

lib/fprime/               F´ framework submodule
```

## Known gotchas

- **Old F´ processes hold port 50001.** If you kill YAMCS and F´
  together and relaunch, the F´ binary sometimes survives the kill
  and blocks the next instance with `errno 98` (EADDRINUSE). Check
  with `ss -tulnp | grep 50001` and `kill -9 <pid>` if needed.
- **`fprime-yamcs-events` requires `FPRIME_DICTIONARY`.** Without it,
  the events subprocess restart-loops and no F´ events reach YAMCS.
  F´ commands still work, but the file browser (which relies on
  `DirectoryListing` events) will not.
- **The `fileManager.schedIn` topology wiring.** The vendored F´
  deployment explicitly wires `fileManager.schedIn` to `rateGroup1`
  because F´'s `ListDirectory` processes entries asynchronously per
  rate tick. Without this, a listing command starts but never
  emits any entries.
- **Two separate `Event` protobuf types in YAMCS.** Code subscribing
  to `events_realtime` must use `org.yamcs.yarch.protobuf.Db.Event`,
  not `org.yamcs.protobuf.Event`. They have identical getters but
  are wire-incompatible.
- **Regex-parsed event args.** `fprime-yamcs-events` publishes F´
  events with the structured arg map discarded (only the rendered
  message string survives). The file listing code regex-parses that
  message to recover file names and sizes. A one-line upstream
  patch would let us use `Event.getExtra()` instead.

## Related PRs

- **`fprime-xtce`** — adds `Fw::FilePacket` XTCE containers to the
  generator. Branch pushed to the author's fork at
  `yudataguy/fprime-xtce:feat/fw-filepacket-containers`, ready for PR
  against `fprime-community/fprime-xtce:main`.
