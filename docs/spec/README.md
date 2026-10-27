# Specifications copied into this repository

| File | Canonical copy | Rule |
|---|---|---|
| `hfrecord-1.schema.json` | Gilly's project files, `heliad/processing-record/hfrecord-1.schema.json` (2026-10-23); its `$id` is `https://gilly.space/heliosoftware/spec/hfrecord-1.schema.json` | A byte copy (sha256 `f92e4dbb8eec3e8685187c70222530fd39c518c7694fc9e8ff96060828e86689`). Do not edit it here: change the canonical file, then copy it again. |
| `../../resources/data/attribution.json` | GillySpace27.github.io `heliosoftware/spec/attribution.json` (2026-10-23, with `credits`) | A byte copy (sha256 `c06146fcd5c166c561f044bb7d56353388e96ffb925c3c702ad68de82cdd75be`) on the classpath, read by `src/org/helioviewer/jhv/movie/InstrumentCredit.java` for the export credit line and each layer's acknowledgement. Copy it again when the canonical file changes. |

Studio writes processing records with `src/org/helioviewer/jhv/movie/ProcessingRecord.java`;
`extra/test/test_hfrecord.py` validates them against this schema in `ant check-all`.

Send to Python (`src/org/helioviewer/jhv/io/FitsExport.java`) also puts the record inside each FITS
file: an 8-bit image extension named `HFRECORD` holding the record as UTF-8 JSON (HelioFITS Core's reader takes
that layout), the cards `HFRECID` (record_id) and `HFRSRC` (the source file's SHA-256), and a HISTORY
block from `HFREC 1.0 record <record_id>` through one card per step applied to `HFREC 1.0 sidecar
<file>.hfrec.json`. `hfrecord.json` beside `manifest.json` covers the whole export, and the manifest
names it under `hfrecord`. `extra/test/FitsRecordCheck.java` and `test_hfrecord.py` check both.
