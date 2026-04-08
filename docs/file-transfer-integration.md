# File Transfer Integration: F´ ↔ YAMCS

**Status:** Draft design — not yet implemented
**Ticket:** Open-Source-Space-Foundation/proves-core-reference#338
**Author:** initial draft from investigation session
**Date:** 2026-04-08

## Problem

The cubesat runs F Prime flight software, which uses `Svc::FileUplink` and
`Svc::FileDownlink` to move files to/from the ground. These components frame
file transfers using the `Fw::FilePacket` wire format (custom to F´).

YAMCS, the intended primary ground system, only ships a CFDP file transfer
service. CFDP and `Fw::FilePacket` are different wire formats and YAMCS has no
built-in understanding of the F´ format.

The user story: operators must be able to send and receive files through
YAMCS, without modifying flight software.

## Current state of this repo (verified, not assumed)

### F´ side — file path is wired up

`FprimeYamcsReference/YamcsDeployment/Top/topology.fpp` lines 71–76 show that
file packets are already framed in CCSDS and routed through the comm stack:

```
FileHandling.fileDownlink.bufferSendOut -> ComCcsds.comQueue.bufferQueueIn[FILE]
ComCcsds.comQueue.bufferReturnOut[FILE]   -> FileHandling.fileDownlink.bufferReturn
ComCcsds.fprimeRouter.fileOut             -> FileHandling.fileUplink.bufferSendIn
FileHandling.fileUplink.bufferSendOut     -> ComCcsds.fprimeRouter.fileBufferReturnIn
```

So the spacecraft already emits and consumes `Fw::FilePacket`-formatted bytes
inside CCSDS space packets on the existing UDP link. **No flight-software
changes are needed.**

### YAMCS side — file packets arrive and go nowhere

`fprime_yamcs/yamcs/src/main/yamcs/etc/yamcs.fprime-project.yaml` (the YAMCS
config shipped by the `fprime-yamcs` Python package) registers 12 services:

1. `org.yamcs.archive.XtceTmRecorder`
2. `org.yamcs.archive.ParameterRecorder`
3. `org.yamcs.archive.AlarmRecorder`
4. `org.yamcs.archive.EventRecorder`
5. `org.yamcs.archive.ReplayServer`
6. `org.yamcs.parameter.SystemParametersService`
7. `org.yamcs.ProcessorCreatorService`
8. `org.yamcs.archive.CommandHistoryRecorder`
9. `org.yamcs.parameterarchive.ParameterArchive`
10. `org.yamcs.plists.ParameterListService`
11. `org.yamcs.timeline.TimelineService`
12. `org.yamcs.ProcessRunner` (runs `fprime-yamcs-events`)

Notably absent:

- No `org.yamcs.cfdp.CfdpService`
- No `cfdp_in` / `cfdp_out` streams in `streamConfig`
- No file bucket configured

The XTCE dictionary (`fprime.xtce.xml`) defines the `FW_PACKET_FILE = 3` enum
value and telemetry counters like `fileUplink.FilesReceived`, but **does not
contain any `SequenceContainer` describing the `Fw::FilePacket` layout
itself**. There is no container that tells YAMCS how to parse the body of a
file packet.

**Net effect:** F´ sends file packets out over UDP. YAMCS receives them. The
packet preprocessor sees an unknown payload type and the bytes are archived
as raw telemetry. Files are never reassembled, never stored, never sent.

The ticket framed this as "YAMCS uses CFDP, F´ doesn't, they're
incompatible." A more precise statement is: **YAMCS in this repo currently
does nothing with file packets at all** — neither the F´ format nor CFDP.

## Wire format reference: `Fw::FilePacket`

Extracted from `lib/fprime/Fw/FilePacket/FilePacket.hpp`.

| Packet      | Layout                                                                | Size              |
| ----------- | --------------------------------------------------------------------- | ----------------- |
| Header      | `U8 type` + `U32 sequenceIndex`                                       | 5 bytes           |
| StartPacket | Header + `U32 fileSize` + `PathName srcPath` + `PathName destPath`    | variable          |
| DataPacket  | Header + `U32 byteOffset` + `U16 dataSize` + `U8[dataSize] data`      | 11 + N bytes      |
| EndPacket   | Header + `U32 checksum` (CFDP modular checksum)                       | 9 bytes           |
| CancelPacket| Header only                                                           | 5 bytes           |
| PathName    | `U8 length` + `char[length]` (no null terminator)                     | 1 + N bytes       |

Packet type discriminator (`Fw::FilePacket::Type`):

| Value | Name      |
| ----- | --------- |
| 0     | T_START   |
| 1     | T_DATA    |
| 2     | T_END     |
| 3     | T_CANCEL  |
| 255   | T_NONE    |

Properties of the protocol:

- **Fire-and-forget.** No ACKs in either direction.
- **Sequence-indexed within a single transfer.** Out-of-order packets trigger
  a `PacketOutOfOrder` warning on the F´ side.
- **Checksum is CFDP-compatible.** F´ pulls in `CFDP::Checksum` from
  `lib/fprime/CFDP/Checksum/`. The End packet's `U32 checksum` is the standard
  CFDP modular file checksum, computed by F´ today and validatable on the
  ground without re-implementation.
- **No transaction ID.** A transfer is identified by the contiguous run of
  packets between a Start and an End sharing a sequence-index range.
- **Max file size: 4 GiB** (`FileDownlink` SDD).
- **Packet data size is configurable** at instantiation
  (`downlinkPacketSize`).

## Options considered

### Option A — Parallel ground systems (F´ GDS for files, YAMCS for everything else)

Run `fprime-gds` in parallel with YAMCS, using F´ GDS only for file transfer.

**Pros:** Zero new code. Operational tomorrow.
**Cons:** Doesn't actually fulfill the user story — files never flow through
YAMCS. YAMCS becomes a partial console. Operators would have to context-switch
between two ground systems for routine file ops.

**Verdict:** Workaround, not a solution. Useful as an emergency fallback but
not the deliverable for this ticket.

### Option B — CFDP middleware translator

A standalone Python service translates CFDP PDUs ↔ `Fw::FilePacket`.

**How it would work:**

1. Configure YAMCS to enable `org.yamcs.cfdp.CfdpService`, with the
   `cfdp_in` / `cfdp_out` streams wired to a CCSDS-framed UDP transport.
2. Stand up a Python translator process between YAMCS and the F´ binary.
3. Translator strips CCSDS framing, reads CFDP PDUs from the YAMCS side and
   `Fw::FilePacket` packets from the F´ side, converts in both directions,
   re-frames, and forwards.

**Why it's hard:**

- **State machine impedance mismatch.** CFDP is conversational
  (Metadata → FileData… → EOF → ACK → Finished). F´ is fire-and-forget. The
  translator must synthesize fake ACK and Finished PDUs back to YAMCS on
  behalf of the spacecraft.
- **Transaction ID mapping.** CFDP transaction IDs and F´ sequence indices
  live in different namespaces and must be tracked.
- **Re-chunking.** CFDP `maxPduSize` and F´ `downlinkPacketSize` must agree
  or the translator must repacketize.
- **Cancel and error semantics.** CFDP and F´ disagree on how to abort.
- **A second deployable artifact.** The translator is a new long-running
  process that has to be installed, configured, and monitored alongside YAMCS.
- **Doesn't use the XTCE dictionary work** that @LeStarch is doing — under
  Option B, YAMCS only ever sees CFDP PDUs, so F´ FilePacket containers in
  the dictionary would be irrelevant to file transfer.

**Pros:** Operators get YAMCS's prebuilt CFDP web UI for browsing transfers.

**Verdict:** Workable but heavy. Most of the complexity exists to bridge a
state machine difference that adds no value to the user story.

### Option C — Native `Fw::FilePacket` handling inside YAMCS *(recommended)*

Teach YAMCS to read and write `Fw::FilePacket` directly. Skip CFDP entirely.

**How it would work:**

1. **Add XTCE containers** to `fprime.xtce.xml` that describe each
   `Fw::FilePacket` variant (Header, StartPacket, DataPacket, EndPacket,
   CancelPacket). These let YAMCS parse incoming file packets natively
   instead of treating them as opaque blobs. **This is the work the ticket
   already references @LeStarch as doing.**

2. **Add a new YAMCS service**, e.g.
   `org.proves.yamcs.fprime.FprimeFilePacketService`, registered as service
   #13 in `yamcs.fprime-project.yaml`:

   ```yaml
     - class: org.proves.yamcs.fprime.FprimeFilePacketService
       args:
         incomingBucket: "fprimeFilesIn"
         outgoingBucket: "fprimeFilesOut"
         tmStream: "tm_realtime"
         tcStream: "tc_realtime"
         fileApid: <APID assigned to file packets>
         downlinkPacketSize: 256  # match F´ instance config
   ```

3. **Inbound (downlink) behavior** of the service:
   - Subscribe to the `tm_realtime` stream.
   - Filter packets matching `fileApid`.
   - Demultiplex by `Fw::FilePacket::Type`.
   - On `T_START`: open a new in-memory transfer keyed by destination path,
     remember declared file size and source path.
   - On `T_DATA`: write payload at the declared `byteOffset` into the
     transfer's reassembly buffer. Track the sequence index for warnings.
   - On `T_END`: validate the CFDP modular checksum against the assembled
     file. On success, drop the file into the `fprimeFilesIn` bucket and
     emit a YAMCS event. On failure, emit an alarm event and discard.
   - On `T_CANCEL`: drop the in-flight transfer, emit an event.

4. **Outbound (uplink) behavior** of the service:
   - Expose a YAMCS API endpoint or watch a bucket for files queued for
     upload.
   - For each queued file, emit a sequence of `T_START` → `T_DATA`(×N) →
     `T_END` packets, sized per `downlinkPacketSize`, framed as CCSDS space
     packets on `fileApid`, written to `tc_realtime`.
   - Compute the End packet's checksum using YAMCS's existing CFDP checksum
     code (YAMCS already ships CFDP libraries even if the service isn't
     enabled).

5. **No flight software changes. No external translator process. No CFDP.**

**Pros:**

- Smallest surface area. One Java class plus XTCE container definitions.
- No state machine impedance — F´ FilePacket is naturally a sequence of
  independent fire-and-forget messages, which maps cleanly onto YAMCS's
  packet/stream model.
- Uses the XTCE dictionary work the team is already doing.
- File transfer flows through YAMCS as a first-class concept, satisfying the
  user story.
- One deployable artifact (YAMCS) instead of two (YAMCS + translator).

**Cons:**

- Operators do not get YAMCS's prebuilt CFDP web UI. They get a custom
  bucket/event-based UX. (For PROVES this is probably acceptable; it can be
  papered over with a small YAMCS web extension later if desired.)
- Requires writing Java in the YAMCS plugin layer. If the team is strictly
  Python, this is a real cost.

**Verdict:** Recommended. It is the most direct path from the verified
current state to the user story, and it aligns with what the dictionary work
is already producing.

## Comparison

|                                          | Option A      | Option B      | **Option C** |
| ---------------------------------------- | ------------- | ------------- | ------------ |
| Flight-software changes                  | None          | None          | None         |
| New long-running process to deploy       | None          | Translator    | None         |
| Protocol state machine work              | None          | Significant   | None         |
| Uses @LeStarch's XTCE dictionary work    | No            | No            | **Yes**      |
| Files actually flow through YAMCS        | **No**        | Yes           | Yes          |
| Operators get YAMCS CFDP web UI          | N/A           | Yes           | No           |
| Approximate code footprint               | 0             | ~300+ lines   | ~150 lines   |
| Implementation language                  | n/a           | Python        | Java         |

## Vision: operator workflow under Option C

This section walks through what file transfer actually *looks like* to a
YAMCS operator once Option C is in place. It is not a protocol spec — see
the wire format reference and Option C behavior sections above for that.

### Downlink (spacecraft → operator)

**Goal:** retrieve `/sd0/log_2026-04-08.bin` from the spacecraft.

1. Operator issues a normal F´ command from the YAMCS command stack:
   `FileDownlink.SendFile("/sd0/log_2026-04-08.bin",
   "downlinks/log_2026-04-08.bin")`. This already works today — it is a
   standard F´ command in the existing XTCE dictionary.
2. Spacecraft `FileDownlink` chops the file into `Fw::FilePacket`s
   (Start, Data×N, End) and emits them as CCSDS packets on the file APID
   over the existing TM UDP link.
3. YAMCS UDP TM link receives the packets and routes them to the
   `tm_realtime` stream.
4. `FprimeFilePacketService` (the new service) is subscribed to
   `tm_realtime`, filters for the file APID, and reassembles the file:
   - On `T_START`: opens an in-memory transfer, allocates a buffer of the
     declared file size, fires a YAMCS event `FileTransferStarted`.
   - On each `T_DATA`: writes payload at `byteOffset`, updates a progress
     counter exposed as a YAMCS system parameter (so operators see a
     progress bar in the YAMCS UI without any custom widget).
   - On `T_END`: validates the CFDP modular checksum against the buffer.
     On match: writes the assembled bytes into the `fprimeFilesIn` bucket
     and fires `FileTransferCompleted`. On mismatch: fires
     `FileTransferFailed` and discards.
5. Operator opens YAMCS web UI → Buckets → `fprimeFilesIn` and downloads
   the file with one click. Buckets are a built-in YAMCS feature with a
   web UI, REST API, and CLI (`yamcs storage`) — all available for free.

**Why buckets matter:** YAMCS already provides a generic file storage
abstraction with web UI, REST API, CLI, and access control. The new
service does not need to build a "file browser" — it just deposits bytes
into a bucket and YAMCS handles the rest.

**Why progress as a system parameter matters:** if the service publishes
`bytes_received` as a regular YAMCS parameter, it gets archived, alarmed,
plotted, and exposed via the existing parameter API like any other
telemetry channel. No custom UI required for "is my transfer progressing?"

### Uplink (operator → spacecraft)

**Goal:** push `new_sequence.bin` from the operator's laptop to
`/sd0/sequences/new_sequence.bin` on the spacecraft.

The interesting design question is *how does the operator trigger an
uplink?* Three reasonable options:

#### Option U1 — YAMCS command *(recommended)*

Define a new YAMCS command `UplinkFile` in the XTCE dictionary with two
arguments: source bucket object name and destination spacecraft path.
The command flows through YAMCS's normal command stack, but the receiving
end is `FprimeFilePacketService` (registered as a command handler), not
the TC link.

Workflow:

1. Operator drags `new_sequence.bin` into the `fprimeFilesOut` bucket via
   the YAMCS web UI, CLI, or REST API.
2. Operator issues `UplinkFile(bucket="fprimeFilesOut",
   obj="new_sequence.bin", dest="/sd0/sequences/new_sequence.bin")` from
   the YAMCS command stack.
3. Service reads bytes from the bucket, computes the CFDP modular
   checksum, generates `T_START` → `T_DATA`(×N) → `T_END` packets sized to
   `downlinkPacketSize`, frames each as a CCSDS packet on the file APID,
   and writes them to `tc_realtime`. The existing UDP TC link ships them
   to the spacecraft.
4. Spacecraft `FileUplink` receives, reassembles, validates, writes to
   disk, and fires its own F´ event. YAMCS already archives that event via
   its event recorder.
5. Operator sees confirmation in the YAMCS event log.

**Why U1 is best:** uplink is a command, and YAMCS already has a complete
command-history, authorization, command-stack, and verifier pipeline.
Modeling uplink as a command gets queueing, audit trail, and access
control for free.

#### Option U2 — Bucket-watch trigger

Service watches `fprimeFilesOut`. Any new object automatically triggers
an uplink to a path derived from object metadata.

- Pros: drag-and-drop UX, no command needed.
- Cons: implicit, hard to authorize, hard to retry, hard to specify
  destination path. Worse for ops than U1.

#### Option U3 — Pure REST API

Add an HTTP endpoint `POST /api/fprime-files/uplink` on the new service.
Operators or scripts call it directly.

- Pros: scriptable.
- Cons: bypasses command history. Harder for human operators.

**Recommended:** U1 as the primary operator path. U3 added later if
scripted automation is needed. Skip U2.

### What the spacecraft never knows

In this entire vision, the spacecraft does not know YAMCS exists. It does
not know files are being archived in buckets. It does not know there is a
custom YAMCS service. From its perspective it is sending and receiving the
same `Fw::FilePacket`s it has always sent. All of the new logic lives on
the ground, in one Java class plus XTCE container definitions.

### Honest gaps in this vision

1. **Lossy links.** If a `T_DATA` packet drops between spacecraft and
   ground, F´ has no retransmit. The transfer's checksum will fail and
   the operator must retry the whole transfer. CFDP Class 2 has NAK-based
   reliability for exactly this reason. If link loss is a realistic
   concern for PROVES, Option C inherits F´'s "best effort" semantics, and
   a retry-on-failure layer would need to be added later. Not a v0
   problem.
2. **Concurrent transfers.** v0 supports one transfer at a time per
   direction. F´ `FileUplink` and `FileDownlink` are themselves
   single-channel, so this matches reality.
3. **No CFDP interoperability.** If a future mission needs to talk to a
   spacecraft that does speak CFDP, this service does not help — enable
   YAMCS's standard `CfdpService` in parallel on a different APID. The
   two services can coexist on different streams.
4. **Operator UX is bucket-based, not transfer-based.** Operators see
   "files in a folder," not "transfer #47 is 73% complete with 2 retries."
   If a transfer-centric view matters, build a small YAMCS web extension
   later. Out of scope for v0.

## Testing strategy

Testing for Option C is layered. Cheaper, faster tests run continuously
in CI; more realistic tests run less often. Each layer covers what the
layer below cannot.

```
                       ┌──────────────────────────┐
                       │   L5: hardware loop      │   manual, slow,
                       │   real spacecraft / radio│   highest fidelity
                       └──────────────────────────┘
                   ┌──────────────────────────────────┐
                   │   L4: end-to-end, real F´ binary │   minutes
                   └──────────────────────────────────┘
               ┌──────────────────────────────────────────┐
               │   L3: end-to-end, fake spacecraft        │   seconds,
               │   (Python harness, real YAMCS)           │   the ticket's
               │                                          │   recommended
               │                                          │   starting point
               └──────────────────────────────────────────┘
           ┌──────────────────────────────────────────────────┐
           │   L2: YAMCS service integration tests            │   ms, in CI
           └──────────────────────────────────────────────────┘
       ┌──────────────────────────────────────────────────────────┐
       │   L1: Java codec unit tests                              │   <1s,
       │   known byte vectors → struct → bytes round-trip         │   in CI
       └──────────────────────────────────────────────────────────┘
```

### L1 — Java codec unit tests

Pure functions: encode and decode each `Fw::FilePacket` variant against
known byte vectors. No YAMCS, no IO, no threading.

**Cheat code:** F´ already ships its own GTest suite for `Fw::FilePacket`
at `lib/fprime/Fw/FilePacket/GTest/`. Use it as a cross-implementation
oracle by either:

- Modifying the F´ tests to dump bytes to disk once and committing the
  resulting `.bin` files as Java test resources, or
- Writing a small C++ utility that uses `Fw::FilePacket` to encode known
  inputs and emit golden `.bin` files. Run once, commit, never touch
  again.

If the Java decoder produces the same struct that F´'s C++ encoder
started with, the wire formats agree bit-for-bit. This is the most
important test in the whole strategy because every higher layer depends
on the wire format being correct.

### L2 — YAMCS service integration tests

Run YAMCS in embedded test mode (its built-in JUnit harness). For each
test:

1. Spin up an instance with `FprimeFilePacketService` configured.
2. Inject bytes into `tm_realtime` as if they came from the UDP TM link.
3. Assert that the file appears in the `fprimeFilesIn` bucket with the
   expected contents and that the expected events fired.
4. For uplink: issue `UplinkFile` via the YAMCS internal API, capture
   what the service writes to `tc_realtime`, decode, and assert it
   matches a known FilePacket sequence.

L2 covers everything *inside* YAMCS — service startup, stream wiring,
bucket access, command handling — without involving any external
process.

### L3 — End-to-end without the F´ binary *(start here)*

This is the level the ticket explicitly endorses: "We can start testing
with the standard YAMCS reference (no FPRIME) and make progress."

A small Python harness (~80 lines using `spacepackets`) plays the role
of the spacecraft on `127.0.0.1:50000` (TM out) and `127.0.0.1:50001`
(TC in).

**Downlink test:**

1. Pick a known file (e.g. 50 KB of random bytes), hash it.
2. Python harness encodes it as `Fw::FilePacket` (Start + Data×N + End),
   wraps each in a CCSDS packet on the file APID, and sends to YAMCS UDP
   TM.
3. Wait up to 5 s for the file to appear in the `fprimeFilesIn` bucket
   (poll via `yamcs-client`, already in `requirements.txt`).
4. Hash the bucket contents. Assert equal to the original.

**Uplink test:**

1. Drop a known file into the `fprimeFilesOut` bucket via `yamcs-client`.
2. Issue the `UplinkFile` command via `yamcs-client`.
3. Python harness `recv()`s on YAMCS's TC port, collects FilePacket
   bytes, reassembles, hashes.
4. Assert equal to the original.

L3 is the workhorse layer. Most of the ongoing test investment should
live here because it tests the real YAMCS service against the real wire
format without the slow F´ build/run cycle.

### L4 — End-to-end with the real F´ binary

Same shape as L3, but with the actual `FprimeYamcsReference_YamcsDeployment`
binary in place of the Python harness.

**Downlink:**

1. Build and launch the F´ deployment via `fprime-yamcs` per the README.
2. Use `yamcs-client` to issue `FileDownlink.SendFile` for a file that
   exists on the F´ side (or seed one via `FileManager` first).
3. Poll the bucket. Hash. Assert.

**Uplink:**

1. Drop a file into the bucket.
2. Issue `UplinkFile`.
3. Wait for F´'s `fileUplink.FileReceived` event in YAMCS's event log
   (already in the dictionary).
4. Optionally read the file back via `FileManager` and verify length.

These cycles take minutes (build time) but catch end-to-end issues —
APID mismatches, framing oddities, drift between XTCE and the F´
topology — that L3 cannot see.

### L5 — Hardware in the loop

The L4 tests, but the F´ binary runs on the actual cubesat or a
flight-equivalent board, talking over the actual radio link or a
serial-to-UDP bridge. Manual, low-frequency, high-cost.

L5 is where:

- Real packet loss, jitter, and link rate behavior surface.
- Timing under realistic CPU and downlink-rate constraints can be
  measured.
- The "lossy links" gap from the design (v0 has no retransmit) actually
  bites — and where the team will learn whether "retry the whole
  transfer" is acceptable in practice.

### Properties to test explicitly

Beyond "does a file move," the following cases should be covered
deliberately:

| Property                                  | Why it matters                          | Layer |
| ----------------------------------------- | --------------------------------------- | ----- |
| Empty file (0 bytes)                      | Off-by-one in "did we get all DATA"     | L1, L3 |
| File exactly 1 packet long                | Boundary: do we need DATA packets at all| L1, L3 |
| File exactly N × packetSize long          | Last DATA has a full payload            | L3    |
| File N × packetSize + 1 byte long         | Last DATA has a 1-byte payload          | L3    |
| Large file (>1 MB)                        | Reassembly buffer behavior              | L3, L4 |
| Path with non-ASCII characters            | PathName encoding                       | L1    |
| Maximum-length path (255 bytes)           | PathName length field boundary          | L1    |
| Out-of-order DATA packets                 | F´ warns; the service should too        | L2    |
| Missing DATA packet                       | Checksum must fail; no false success    | L2, L3 |
| Wrong checksum on End                     | Reject file, fire alarm                 | L2    |
| Two transfers back-to-back                | Service must reset state cleanly        | L3    |
| Cancel packet mid-transfer                | v0: log; v1: real handling              | L2    |
| **Round-trip identity** (uplink→downlink) | Catches any wire bug in either direction| L4    |

The round-trip test at L4 is the single most useful test in the suite —
it catches almost any wire format or framing bug in either direction
simultaneously, and it makes a good pre-flight-rehearsal smoke test.

### Recommended sequencing for test work

1. **L1** — codec unit tests with golden vectors generated from F´'s own
   C++. The foundation; everything above depends on it.
2. **L3 downlink** against vanilla YAMCS with the Python fake spacecraft.
3. **L3 uplink.**
4. **L4** with the real F´ binary, downlink first, then uplink.
5. **L2** unit tests filled in retroactively to lock down any regression
   L3 caught.
6. **L5** hardware loop when flight-equivalent hardware is available.

L2 is intentionally not first: its setup cost (embedded YAMCS) is high
and its unique coverage relative to L3 is low. Only build L2 when there
is a specific bug worth pinning with a fast in-CI regression test.

## Recommended path forward

1. **Confirm Option C with the ticket author and @LeStarch.** Specifically
   confirm that the dictionary work already in flight is producing XTCE
   containers for `Fw::FilePacket`, not CFDP PDUs. If yes, Options C and the
   dictionary work are the same project.
2. **Define the file APID** assigned to `Fw::FilePacket` traffic in this
   deployment. This must agree between F´'s `ComCcsds` config, the XTCE
   container `RestrictionCriteria`, and the new YAMCS service config.
3. **Land the XTCE containers** for Header, StartPacket, DataPacket,
   EndPacket, CancelPacket in `fprime.xtce.xml`.
4. **Implement `FprimeFilePacketService`** as a YAMCS plugin. Start with
   downlink only — it is self-contained and easier to test in isolation
   (drop a file out of the running F´ binary, verify it appears in the
   bucket).
5. **Add uplink** as a follow-up once downlink is verified end-to-end.
6. **Document the operator workflow**: how to retrieve a downlinked file
   from the bucket; how to queue a file for uplink.

## Resolved facts (from this deployment, verified)

- **File packet APID: `0x0003`** (decimal 3), defined as `FW_PACKET_FILE` in
  `lib/fprime/default/config/ComCfg.fpp:27`. This deployment does not
  override the default ComCfg, so the value is in effect as-is. Triple-
  confirmed:
  1. F´ default config: `FW_PACKET_FILE = 0x0003`
  2. XTCE dictionary already lists it:
     `fprime.xtce.xml:174` → `<Enumeration value="3" label="FW_PACKET_FILE">`
  3. YAMCS data link config uses `spacecraftId: 68` which matches the
     ComCfg `SpacecraftId = 0x0044`, confirming both sides agree on
     framing parameters.
- **Spacecraft ID: `0x0044`** (decimal 68).
- **TM frame fixed size: 1024 bytes.**
- F´'s `ComCfg::Apid` enum values are constrained by `static_assert` to
  match `Fw::ComPacketType` exactly (see `ApidManager.hpp:21-31`), so the
  CCSDS APID *is* the F´ packet-type discriminator. No room for the
  outer and inner discriminators to disagree.
- **XTCE dictionary direction is `Fw::FilePacket`, not CFDP.** Verified by
  reading `fprime.xtce.xml` directly. The dictionary follows a clear
  pattern of one abstract `SequenceContainer` per F´ APID, gating on
  `CCSDS_Packet_ID/APID`:

  | Container               | APID | Status        |
  | ----------------------- | ---- | ------------- |
  | (commands, line 2573)   | 0    | present       |
  | `FPrimeTelemetryChannel`| 1    | present       |
  | `FPrimeEvent`           | 2    | present       |
  | **(file packets)**      | **3**| **missing — the gap** |
  | `FPrimeTelemetryPacket` | 4    | present       |

  There are **zero** CFDP-related entries anywhere in the dictionary
  (`grep -i cfdp` → 0 hits), and no `cfdp_in`/`cfdp_out` streams in the
  YAMCS config. The CFDP path was never started. The only direction
  consistent with the existing dictionary structure is to add an
  `FPrimeFilePacket` abstract container gated on APID 3, with concrete
  Start/Data/End/Cancel children gated further on the `Fw::FilePacket`
  header type field. This is a copy-paste-modify of the existing
  `FPrimeEvent` block (the simplest existing container) plus payload
  entries — no novel XTCE machinery needed.

## Open questions

- Java plugin authoring is accepted as a cost worth paying — it unlocks
  YAMCS as the primary ground system. First-plugin friction (build/load
  cycle, packaging) should be expected.
- For uplink, what is the operator-facing trigger? A YAMCS command? A
  bucket drop? An HTTP API call? Recommend U1 (command) per the Vision
  section, but final call belongs to whoever will operate the system.
- How should partial transfers be surfaced to operators (timeouts, missing
  packets, checksum failures)? Probably YAMCS events plus an alarm channel.

## Out of scope for this design

- Multi-file or concurrent transfers (v0 can support one in-flight transfer
  per direction).
- Resumable transfers across YAMCS restarts.
- CFDP interoperability with non-F´ spacecraft. If the same YAMCS instance
  later needs to talk to a CFDP-speaking spacecraft, the standard
  `CfdpService` can be enabled in parallel — it would not conflict with
  `FprimeFilePacketService` because they would handle different APIDs.
