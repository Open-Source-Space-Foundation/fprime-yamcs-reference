# `fprime-yamcs-events` patch — publish F´ event arguments as `Event.extra`

A one-line patch to the `fprime-yamcs` package's event processor that
propagates F´ event arguments through to the YAMCS `Event.extra` map.

## Why this patch exists

`fprime-yamcs-events` is the Python subprocess that sits inside the
YAMCS instance and decodes F´ event packets from the TM stream into
YAMCS events. Its `processor.py:189-195` already extracts F´ event
arguments into a structured dict:

```python
event_args = {}
if event_data.args:
    arg_templates = event_data.template.get_args()
    for idx, arg in enumerate(event_data.args):
        arg_name, _, _ = arg_templates[idx]
        event_args[arg_name] = str(arg.val)
```

But a few lines later, `event_args` is *not* passed to
`yamcs_client.send_event()`:

```python
self.yamcs_client.send_event(
    instance=self.yamcs_instance,
    source='FPrimeEventProcessor',
    event_type=event_name,
    severity=yamcs_severity,
    message=message,
)
```

Result: the published YAMCS Event has an empty `extra` map. Downstream
consumers that want structured access to event arguments — e.g. our
`FprimeFilePacketService.EventTupleSubscriber` decoding
`DirectoryListing` events for the remote file browser — have to
regex-parse the rendered `message` string. That's fragile against F´
format-string changes and loses type information.

## What the patch does

One edit in `src/fprime_yamcs/events/processor.py`:

```python
self.yamcs_client.send_event(
    ...,
    message=message,
    extra=event_args if event_args else None,  # ← added
)
```

`yamcs-client`'s `send_event()` already accepts an optional
`extra: Mapping[str, str]` parameter that sets `Event.extra` on the
published protobuf. Passing the already-built `event_args` dict through
it preserves the structured data all the way to YAMCS.

No functional change for existing consumers that only read
`Event.message` — they continue to see the same rendered string.

## How to apply locally

```sh
cp dict-additions/fprime_yamcs_events_patch/processor.py \
   fprime-venv/lib/python3.11/site-packages/fprime_yamcs/events/processor.py
```

Then restart YAMCS so the ProcessRunner picks up the new Python source.

## Upstream status

Pushed as a fork branch, ready to PR:
`yudataguy/fprime-yamcs:feat/publish-event-args` →
`fprime-community/fprime-yamcs:main`.

After the upstream PR lands and the `fprime-yamcs` package is
republished to PyPI, this directory can be deleted and `requirements.txt`
bumped.

## Verification

Before the patch, `Event.getExtra()` returned empty. After the patch,
a `DirectoryListing` event from F´ carries:

```json
{
  "type": "DirectoryListing",
  "message": "[DirectoryListing] Directory .: Main.cpp (3170 bytes)",
  "extra": {
    "dirName": ".",
    "fileName": "Main.cpp",
    "fileSize": "3170"
  }
}
```

The `FprimeFilePacketService.EventTupleSubscriber` reads `extra.get("dirName")`,
`extra.get("fileName")`, and `extra.get("fileSize")` directly — no regex.

The regex patterns remain in the service as a fallback for unpatched
`fprime-yamcs-events` installs, so the service works both with and
without this patch applied.
