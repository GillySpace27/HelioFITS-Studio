# Specifications copied into this repository

| File | Canonical copy | Rule |
|---|---|---|
| `hfrecord-1.schema.json` | Gilly's project files, `heliad/processing-record/hfrecord-1.schema.json` (2026-10-23); its `$id` is `https://gilly.space/heliosoftware/spec/hfrecord-1.schema.json` | A byte copy (sha256 `f92e4dbb8eec3e8685187c70222530fd39c518c7694fc9e8ff96060828e86689`). Do not edit it here: change the canonical file, then copy it again. |
| `../../resources/data/attribution.json` | GillySpace27.github.io `heliosoftware/spec/attribution.json` (2026-10-23, with `credits`) | A byte copy (sha256 `c06146fcd5c166c561f044bb7d56353388e96ffb925c3c702ad68de82cdd75be`) on the classpath, read by `src/org/helioviewer/jhv/movie/InstrumentCredit.java` for the export credit line and each layer's acknowledgement. Copy it again when the canonical file changes. |

The schema is no longer that byte copy: it has `scene.pizza` (HS-25), and on 2026-11-05 it took the
top-level `report` property verbatim from pyHelioFITS (`src/heliofits/core/schemas/hfrecord-1.schema.json`).

A sidecar is named `<export file name>.hfrec` (for example `frame.png.hfrec`); the content is the
JSON this schema describes. Studio wrote `<export file name>.hfrec.json` before and reads both
everywhere it reads a sidecar, `.hfrec` first when both are there (`ProcessingRecord.existingSidecar`).
When Studio reads and parses an older `.hfrec.json` (File > Open Export Record, the command line's
`-out` copy, a study's skip-if-unchanged check), it renames it to `.hfrec`, bytes untouched, logging
one info line (`ProcessingRecord.migrateLegacy`): only when the setting Rename old .hfrec.json
sidecars when opened is on (the default; key `export.renameOldSidecars`), no `.hfrec` is there and
the folder is writable, and any failure leaves the old name, which is still read. Open Export Record
shows "Renamed to <name>.hfrec" in its status line when it renamed one. Listing files never renames.
File > Migrate Sidecars in Folder... (`migrateSidecars`, `ProcessingRecord.migrateFolder`) does the
same for a whole folder, recursively and whatever the setting, with HelioFITS Core's `hfcore record
migrate` rule: only files ending in `.hfrec.json` that parse as a record (a JSON object with a string
`hfrecord`), never over an existing `.hfrec`; it shows the count and asks first, then reports what it
renamed and skipped. HelioFITS Peek only reads; it renames nothing.

Studio writes processing records with `src/org/helioviewer/jhv/movie/ProcessingRecord.java`;
`extra/test/test_hfrecord.py` validates them against this schema in `ant check-all`.

Every sidecar starts with `hfrecord`, then `report`: the record as readable lines, from
`src/org/helioviewer/jhv/movie/ExportReport.java`, a port of `text()` in pyHelioFITS's
`src/heliofits/core/report.py`. `extra/test/ExportReportCheck.java` holds it to that module's output
(`python -m heliofits.core record show <record> --format text`, with and without `--full`) line for
line, for the fixtures `extra/test/data/hfrecord-*.hfrec.json` (kept under the older name to prove it is still read). File > Open Export Record shows the
report, and copies the methods paragraph, LaTeX or Python script from the bundled HelioFITS Core.

Send to Python (`src/org/helioviewer/jhv/io/FitsExport.java`) also puts the record inside each FITS
file: an 8-bit image extension named `HFRECORD` holding the record as UTF-8 JSON (HelioFITS Core's reader takes
that layout), the cards `HFRECID` (record_id) and `HFRSRC` (the source file's SHA-256), and a HISTORY
block from `HFREC 1.0 record <record_id>` through one card per step applied to `HFREC 1.0 sidecar
<file>.hfrec`. `hfrecord.json` beside `manifest.json` covers the whole export, and the manifest
names it under `hfrecord`. `extra/test/FitsRecordCheck.java` and `test_hfrecord.py` check both.
