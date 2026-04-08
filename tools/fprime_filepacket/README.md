# `fprime_filepacket` — Python reference codec

A wire-format reference implementation in Python for `Fw::FilePacket` and
the surrounding F´ ComPacket descriptor. Used for two purposes:

1. **Validate** the team's reading of the `Fw::FilePacket` wire format
   independently of any C++ or Java implementation.
2. **Serve as the L3 fake spacecraft** in the testing strategy described
   in `docs/file-transfer-integration.md` — once the new YAMCS service
   exists, this codec is what the L3 Python harness will use to feed
   canned packets at YAMCS without involving the F´ binary.

## Wire format sources

This codec is **not** a port of F´. It is an independent re-implementation
against the public wire format, derived from:

- `lib/fprime/Fw/FilePacket/FilePacket.hpp` — struct layouts
- `lib/fprime/Fw/FilePacket/Header.cpp` — serialization conventions
  (big-endian)
- `lib/fprime/Svc/FileDownlink/FileDownlink.cpp` — the U16 ComPacket
  descriptor prefix that F´ prepends inside the CCSDS payload
- `lib/fprime/CFDP/Checksum/Checksum.cpp` — the CFDP modular checksum
  algorithm used by `EndPacket`

## Running the tests

```
PYTHONPATH=tools python3 tools/fprime_filepacket/test_codec.py
```

Or with pytest:

```
PYTHONPATH=tools python3 -m pytest tools/fprime_filepacket/test_codec.py -v
```

The current tests are hand-rolled byte vectors computed from the wire
format spec. They are a sanity check, not a cross-implementation oracle.
The L1 cross-oracle test (`tools/fprime_filepacket/` decoder against
F´'s own C++ encoder output) is **not yet implemented** — it's listed
as the next step in `docs/file-transfer-integration.md`'s testing
strategy.

## Status

Draft. Sufficient for L3 harness work, not yet validated against F´'s
own C++ codec.
