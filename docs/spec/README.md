# Specifications copied into this repository

| File | Canonical copy | Rule |
|---|---|---|
| `hfrecord-1.schema.json` | Gilly's project files, `heliad/processing-record/hfrecord-1.schema.json` (2026-10-23); its `$id` is `https://gilly.space/heliosoftware/spec/hfrecord-1.schema.json` | A byte copy (sha256 `f92e4dbb8eec3e8685187c70222530fd39c518c7694fc9e8ff96060828e86689`). Do not edit it here: change the canonical file, then copy it again. |

Studio writes processing records with `src/org/helioviewer/jhv/movie/ProcessingRecord.java`;
`extra/test/test_hfrecord.py` validates them against this schema in `ant check-all`.
