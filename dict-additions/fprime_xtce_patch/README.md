# `fprime-xtce` patch for `Fw::FilePacket` containers

This directory contains a working patch to the `fprime-xtce` Python
package that teaches `fprime-to-xtce` to emit XTCE containers for the
F´ `Fw::FilePacket` wire format. The patch was developed and tested
in this repo's `fprime-venv` against `fprime-xtce==0.1.0`.

## Why this patch exists

The XTCE dictionary used by YAMCS (`fprime.xtce.xml`) is **not**
hand-maintained — it is regenerated on every launch by `fprime-yamcs`
calling `fprime-to-xtce`, which in turn reads the F´ JSON dictionary.
Adding new containers by hand to the generated XTCE file would be
overwritten on the next launch. The fix has to live in the generator,
not in the output.

This patch adds the file packet containers to the generator's
`BASE_CONTAINERS` and `BASE_PARAMETERS` lists, plus a `FPrimeFilePathType`
StringParameterType to `BASE_FPRIME_TYPES`.

## What's in this directory

- `primitive_types.py` — patched version of
  `fprime_xtce/primitive_types.py`. Adds `FPrimeFilePathType` (a
  StringParameterType with an 8-bit length prefix, matching
  `Fw::FilePacket::PathName`) and 8 new entries to `BASE_PARAMETERS`.
- `primitive_containers.py` — patched version of
  `fprime_xtce/primitive_containers.py`. Adds 5 new entries to
  `BASE_CONTAINERS`: an abstract `FPrimeFilePacket` (gating on APID 3)
  and 4 concrete subcontainers `FPrimeFilePacket{Start,Data,End,Cancel}`
  (gating on the U8 packet type discriminator).
- `sample_output.xtce.xml` — a real generated XTCE produced by
  running the patched `fprime-to-xtce` against this repo's F´ JSON
  dictionary at
  `build-artifacts/Linux/FprimeYamcsReference_YamcsDeployment/dict/YamcsDeploymentTopologyDictionary.json`.
  Verified XML well-formedness and the presence of all 5 containers
  plus the new parameter types. Use this as a reference of what the
  patched generator produces.

## How to apply locally for testing

```sh
cp dict-additions/fprime_xtce_patch/primitive_types.py \
   fprime-venv/lib/python3.11/site-packages/fprime_xtce/primitive_types.py
cp dict-additions/fprime_xtce_patch/primitive_containers.py \
   fprime-venv/lib/python3.11/site-packages/fprime_xtce/primitive_containers.py
```

Then:

```sh
source fprime-venv/bin/activate
fprime-to-xtce -o /tmp/test.xtce.xml \
  build-artifacts/Linux/FprimeYamcsReference_YamcsDeployment/dict/YamcsDeploymentTopologyDictionary.json
grep FPrimeFilePacket /tmp/test.xtce.xml
```

You should see the 5 new containers and 8 new parameters.

## How to upstream

The right place for this change is the `fprime-xtce` repository on
PyPI. Until the upstream PR lands, this directory is the
canonical source of the change. After it lands and `requirements.txt`
is bumped, this directory can be deleted.

The upstream PR should contain only the `BASE_FPRIME_TYPES`,
`BASE_PARAMETERS`, and `BASE_CONTAINERS` additions — `sample_output.xtce.xml`
is local debugging context, not part of the PR.

## Wire format reference

This patch produces XTCE that decodes the wire format implemented by:

- `lib/fprime/Fw/FilePacket/FilePacket.hpp` — struct layouts
- `lib/fprime/Fw/FilePacket/PathName.cpp` — confirms the U8 length
  prefix used by `FPrimeFilePathType`
- `lib/fprime/Svc/FileDownlink/FileDownlink.cpp:357-359` — the
  `DataDescType = FW_PACKET_FILE = 3` ComPacket descriptor that
  prefixes every file packet inside a CCSDS payload

The same wire format is implemented by the Python reference codec at
`tools/fprime_filepacket/`, which is independently cross-validated
against F´'s C++ encoder by the L1 oracle test at
`tools/fprime_filepacket/oracle/test_against_oracle.py`.
