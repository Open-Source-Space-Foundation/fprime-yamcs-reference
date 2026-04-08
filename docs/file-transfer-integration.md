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

## Open questions

- Which APID is reserved for file packets in this deployment? It needs to be
  agreed across F´ topology config, the XTCE dictionary, and the YAMCS
  service.
- Does the team have prior experience writing YAMCS Java plugins, or is this
  the first one? If first, budget extra time for the YAMCS plugin build/load
  cycle.
- For uplink, what is the operator-facing trigger? A YAMCS command? A bucket
  drop? An HTTP API call? This should be decided with whoever will operate
  the system.
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
