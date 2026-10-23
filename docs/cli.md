# Command line

`./run.sh [options]` (or `java -jar HFStudio.jar [options]`). The help text is generated from the
option table in `CommandLine.java`; this page says what the table cannot.

| Option | Value | Meaning |
|---|---|---|
| `-load` | file or URL | Load or request a supported file at start. Repeatable. |
| `-request` | request file | Load a request file and issue the request. Repeatable. |
| `-state` | `.jhv` file | Load a session; the window then belongs to it (autosave writes back to it). |
| `-export` | `png` | Batch job: draw the scene, write one PNG, exit. Needs `-out`. |
| `-out` | path | Where `-export` writes. |
| `-dataset` | `dataset_uid` or `dataset_key` | Load that Heliad library dataset as one image layer. Repeatable. |
| `-start` | ISO 8601 UTC time | With `-dataset`: the first observation to load. |
| `-end` | ISO 8601 UTC time | With `-dataset`: the last observation to load. |
| `-h` | none | Print the help and exit. |

Every spelling that existed keeps working. A double-dash form of any option (`--state`, `--export`,
`--help`) is an alias. A lone `.jhv` path is `-state <path>`.

## Heliad datasets

`-dataset sdo/aia/-/-/1/193 -start 2025-09-21T16:00:00 -end 2025-09-21T18:00:00` loads the current
files of that dataset in the Heliad library on this computer whose observation time lies in the range,
as one layer, in time order: what Add does on the Heliad tab. The value is looked up as a `dataset_uid`
(when the library's index has that column, Heliad schema 5), then as a `dataset_key`, then as a former key
in `dataset_aliases`. Times are ISO 8601 UTC; a trailing `Z` and fractional seconds are optional
(`2025-09-21T16:00:04.840` is how Heliad writes them). Without `-start` or `-end`
the range is open at that end. One `-start` and `-end` apply to every `-dataset` on the line. With
`-export`, a dataset that cannot be loaded (no library, no such dataset, nothing in the range) ends the
run with exit code 3. The SAMP message `heliad.dataset.load` does the same (`docs/samp-commands.md`).

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Done (an export was written). |
| 1 | Runtime error. |
| 2 | Usage error: unknown option, missing value, `-export` without `-out` or with an unknown format, a `-start` or `-end` that is not a time, or either without `-dataset`. |
| 3 | Input not found: the `-state` file cannot be read, or it failed to load, or a `-dataset` could not be loaded in export mode. |
| 4 | Export failed. |

## Export mode

`-export png -out file` runs the normal application with these differences: nothing is reopened (no
autosave session, no pinned default), no `-state` is appended, the named `-state` is only read, no
autosave timer runs, no welcome window, tour, outbox or update check starts, and the process ends with
`Runtime.halt`, so no shutdown hook writes a session. The PNG is the Screenshot the Movie panel writes
(16-bit RGB, 2048 pixels on a side at the stored size) with the session stamped in as its `hfstudio`
tEXt chunk, and beside it its processing record `<out>.hfrec.json` (hfrecord 1.0,
`docs/spec/hfrecord-1.schema.json`): the layers' sources by SHA-256, every step in the order Studio runs
it with the values used, and the session under `native.studio_session`, from which `-state` rebuilds the
scene (`extra/test/hfrecord_roundtrip.py`, run by `launch.yml`). The PNG also carries a copy of the
record in its own `hfrecord` tEXt chunk (`output.sha256` null, no `output.file_name`, `native_ref`
`png:tEXt:hfstudio` in place of the session) and the suite provenance chunk `HelioSoftware-Provenance`
(plus `RHEF` and `Disclaimer` when a layer has RHEF on). It is written when the scene has an image and 3 s have passed (`-Djhv.exportSettleMs=N`
changes that); after 180 s without an image it exits 4.

The first slice needs a display (use `xvfb-run -a` on a server); the window is not hidden. Movie export
is not part of it. Always give it a throwaway home when testing: `JAVA_TOOL_OPTIONS="-Duser.home=$(mktemp -d)"`.

Smoke test (CI runs it in `launch.yml`):

```sh
xvfb-run -a ./run.sh --export png --out /tmp/x.png -load extra/test/data/sample.171.fits
```
