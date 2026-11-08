# Specifications copied into this repository

| File | Canonical copy | Rule |
|---|---|---|
| `hfrecord-1.schema.json` | Gilly's project files, `heliad/processing-record/hfrecord-1.schema.json` (2026-10-23); its `$id` is `https://gilly.space/heliosoftware/spec/hfrecord-1.schema.json` | A byte copy (sha256 `a30e27dca6dc5856903e40c1e1d738393c5d047d8987f5b206b9b052153f33d5`; hfrecord 1.1, from pyHelioFITS branch `claude/project-thread-4ni954`, commit 5cade65, not yet merged there). Do not edit it here: change the canonical file, then copy it again. |
| `../../resources/data/attribution.json` | GillySpace27.github.io `heliosoftware/spec/attribution.json` (2026-10-23, with `credits`) | A byte copy (sha256 `c06146fcd5c166c561f044bb7d56353388e96ffb925c3c702ad68de82cdd75be`) on the classpath, read by `src/org/helioviewer/jhv/movie/InstrumentCredit.java` for the export credit line and each layer's acknowledgement. Copy it again when the canonical file changes. |

The schema is no longer that byte copy: it has `scene.pizza` (HS-25), and on 2026-11-05 it took the
top-level `report` property verbatim from pyHelioFITS (`src/heliofits/core/schemas/hfrecord-1.schema.json`),
and on 2026-11-06 its top-level `preview` property, verbatim from the same file on pyHelioFITS's branch
`claude/project-thread-j9fagc`.

A sidecar is named `<export file name>.hfrec` (for example `frame.png.hfrec`); the content is the
JSON this schema describes. Studio wrote `<export file name>.hfrec.json` before and reads both
everywhere it reads a sidecar, `.hfrec` first when both are there (`ProcessingRecord.existingSidecar`).
When Studio reads and parses an older `.hfrec.json` (File > Open Export Record, the command line's
`-out` copy, a study's skip-if-unchanged check), it renames it to `.hfrec`, bytes untouched, logging
one info line (`ProcessingRecord.migrateLegacy`): only when the setting Rename old .hfrec.json
sidecars when opened is on (the default; key `export.renameOldSidecars`), no `.hfrec` is there and
the folder is writable, and any failure leaves the old name, which is still read. Open Export Record
shows "Renamed to <name>.hfrec" in its status line when it renamed one. Listing files never renames.
File > Upgrade Sidecars in Folder... (`migrateSidecars`, `ProcessingRecord.migrateFolder`; called
Migrate Sidecars in Folder before 2026-11-06, same command id) does the same for a whole folder,
recursively and whatever the setting, with HelioFITS Core's `hfcore record migrate` rule: only files
ending in `.hfrec.json` that parse as a record (a JSON object with a string `hfrecord`), never over an
existing `.hfrec`. It also adds a preview (below) to every record in the folder that has none. It
shows both counts and asks first, then reports what it renamed, how many previews it added, and what
it skipped. HelioFITS Peek only reads; it renames nothing.

Studio writes processing records with `src/org/helioviewer/jhv/movie/ProcessingRecord.java`;
`extra/test/test_hfrecord.py` validates them against this schema in `ant check-all`.

Every sidecar starts with `hfrecord`, then `report`: the record as readable lines, from
`src/org/helioviewer/jhv/movie/ExportReport.java`, a port of `text()` in pyHelioFITS's
`src/heliofits/core/report.py`. `extra/test/ExportReportCheck.java` holds it to that module's output
(`python -m heliofits.core record show <record> --format text`, with and without `--full`) line for
line, for the fixtures `extra/test/data/hfrecord-*.hfrec.json` (kept under the older name to prove it is still read). File > Open Export Record shows the
report, and copies the methods paragraph, LaTeX or Python script from the bundled HelioFITS Core.

Every sidecar Studio writes for a picture, a movie or a PNG frame series ends with `preview`, a small
picture of the export: `{"media_type": "image/png" | "image/jpeg", "width", "height", "frame"?,
"data"}`, `data` the image file in base64, at most 320 px on the long side and 64 KB of image bytes.
It is embedded because HelioFITS Peek's sandboxed Quick Look and thumbnail extensions cannot read the
export beside the sidecar. Studio makes it at close from the raw frames it handed to ffmpeg
(`ExportWriter.rawFrame`, `ProcessingRecord.preview`): the only frame of a still, the middle frame of
a movie or series (`frame`, counted from 0, names it; a still has none). It is a PNG, or a JPEG at
lower quality and then a smaller size when the PNG is over 64 KB; when nothing fits, the sidecar has
no preview. `sidecarText` writes it last, `data` last inside it, so the report stays at the top; a
rewrite (`copySidecar`) keeps it there, and a rename never touches the bytes. The copies inside a
PNG, EXR, movie or FITS file never carry it (`ProcessingRecord.embedded`), a layered EXR series and a
Send to Python file get none, and ExportReport does not read it, so the report is the same with or
without it. File > Open Export Record shows the preview at the top left beside the export's name;
for a record without one it reads the PNG or JPEG named in `output.file_name` beside the sidecar, and
shows nothing for anything else (`ProcessingRecord.previewImage`). A 16-bit frame is taken to 8 bits by
each sample's high byte, with no tone mapping; the preview of an HDR export has not been checked by eye.

A sidecar written before Studio embedded previews gains one (`ProcessingRecord.addPreview`) when it is
renamed from `.hfrec.json` on read, when Open Export Record opens it, and in Upgrade Sidecars in
Folder; the first two only while the setting above is on. The preview is made by the same encoder and
limits, from the export beside the sidecar named in `output.file_name`: a PNG or JPEG still itself
(downscaled), the middle file of a PNG frame series (`frame` its index in name order), and for a
movie the frame `output.frames / 2` (as at export, placed with `output.fps`) decoded by the ffmpeg
Studio unpacks for exports; with no ffmpeg there, a movie gets none. EXR, FITS, a missing or
unreadable export: none. The file is rewritten only when a preview was made, and then the text gains
`,` and `"preview": {...}` just before its closing brace, so every other byte (key order, number
formatting, indentation) stays as it was; a read-only sidecar or folder is not touched.

Send to Python (`src/org/helioviewer/jhv/io/FitsExport.java`) also puts the record inside each FITS
file: an 8-bit image extension named `HFRECORD` holding the record as UTF-8 JSON (HelioFITS Core's reader takes
that layout), the cards `HFRECID` (record_id) and `HFRSRC` (the source file's SHA-256), and a HISTORY
block from `HFREC 1.0 record <record_id>` through one card per step applied to `HFREC 1.0 sidecar
<file>.hfrec`. `hfrecord.json` beside `manifest.json` covers the whole export, and the manifest
names it under `hfrecord`. `extra/test/FitsRecordCheck.java` and `test_hfrecord.py` check both.

## hfrecord 1.1 additions (Studio writes them; all optional keys)

Studio's record format is `1.1`; a reader that knows 1.0 ignores what it does not know. The keys are defined by the
schema above; what Studio writes into each, and when:

- `environment`: `os`, `jvm`, `gpu` (the GL strings Studio reads at start-up), `display.dither` (the shader dithered this
  render: the setting, unless a deep canvas or a capture deeper than 8 bits skips it) and `display.high_bit_depth_capture`,
  `core.commit` (the HelioFITS Core in the package, from its `HFCORE_COMMIT` file; absent in a build tree) and, for a movie,
  `ffmpeg` (`version`, `encoder`, `arguments` without paths). Never a host name, user name or path. Also inside embedded
  copies. Not written yet: `display.pixel_ratio`, `display.msaa`, `core.version`, `core.python`.
- `output.pixel_sha256` and `output.frame_digests` (sidecar only): `hfpix/1` digests of the frames as handed to the
  encoder, before any codec, the timeline strip included; `pixel_sha256` is the first frame's. The raw dump is little-endian
  at 16 bits and is swapped while hashed. `output.content_sha256`: a PNG still keeping only signature, IHDR, PLTE, tRNS, IDAT
  and IEND; null for a movie. An EXR series takes no digest.
- `frame_map` (movies and frame series, sidecar only): per output frame, the layer's frame number, which is an index into its
  `sources` for a layer with one identified file per frame; other layers are left out and named in `reproducibility`.
- `inputs_external`: the Hipparcos star file (hashed at export start, so pinned), 3D model layers and any overlay this
  code does not know to be deterministic (declared, `pinned: false`), and the LASCO monthly background (declared; the array
  is not hashed).
- `reproducibility`: `exact_replay_possible` is true only with no `reasons`: a development build, a scene that changed during
  the export, a Helioviewer layer, an unidentified source, an unpinned input, a dithered render, no environment block, or no
  pixel digest. `frames_pending` counts source frames not identified when the record was written. It is a statement about
  the record's inputs, never a promise about pixels on another GPU.
