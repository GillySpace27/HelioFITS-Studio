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
| `-h` | none | Print the help and exit. |

Every spelling that existed keeps working. A double-dash form of any option (`--state`, `--export`,
`--help`) is an alias. A lone `.jhv` path is `-state <path>`.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | Done (an export was written). |
| 1 | Runtime error. |
| 2 | Usage error: unknown option, missing value, `-export` without `-out` or with an unknown format. |
| 3 | Input not found: the `-state` file cannot be read, or it failed to load. |
| 4 | Export failed. |

## Export mode

`-export png -out file` runs the normal application with these differences: nothing is reopened (no
autosave session, no pinned default), no `-state` is appended, the named `-state` is only read, no
autosave timer runs, no welcome window, tour, outbox or update check starts, and the process ends with
`Runtime.halt`, so no shutdown hook writes a session. The PNG is the Screenshot the Movie panel writes
(16-bit RGB, 2048 pixels on a side at the stored size) with the session stamped in as its `hfstudio`
tEXt chunk. It is written when the scene has an image and 3 s have passed (`-Djhv.exportSettleMs=N`
changes that); after 180 s without an image it exits 4.

The first slice needs a display (use `xvfb-run -a` on a server); the window is not hidden. Movie export
is not part of it. Always give it a throwaway home when testing: `JAVA_TOOL_OPTIONS="-Duser.home=$(mktemp -d)"`.

Smoke test (CI runs it in `launch.yml`):

```sh
xvfb-run -a ./run.sh --export png --out /tmp/x.png -load extra/test/data/sample.171.fits
```
