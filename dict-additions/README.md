# Dictionary additions

This directory holds proposed additions to the F´ XTCE dictionary that
are not yet upstream in the `fprime-yamcs` Python package. They are
checked in here as drafts so they can be reviewed alongside the rest of
the file transfer integration work in `docs/file-transfer-integration.md`.

## `file_packet_containers.xtce.xml`

Adds parameters, a string parameter type, and `SequenceContainer`s
describing the `Fw::FilePacket` wire format for downlink. Designed to be
merged into the existing `fprime.xtce.xml` shipped by `fprime-yamcs`,
following the same pattern as the existing `FPrimeTelemetryChannel`,
`FPrimeEvent`, and `FPrimeTelemetryPacket` containers.

This file is **not** a standalone XTCE file. It is a fragment. To
actually use it, the contents need to be merged into the upstream
`fprime.xtce.xml` at the locations called out in the file's comments.

The wire format it encodes is verified by the Python reference codec at
`tools/fprime_filepacket/` — both were derived from the same reading of
`lib/fprime/Fw/FilePacket/FilePacket.hpp` and should agree.

## Status

Draft. Coordinate with @LeStarch before merging upstream — they may
already have a parallel version in flight and the naming should align.
