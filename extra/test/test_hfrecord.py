#!/usr/bin/env python3
"""Validate processing records (hfrecord 1.0) against docs/spec/hfrecord-1.schema.json.

    python3 extra/test/test_hfrecord.py                 # the records ProcessingRecordCheck wrote
    python3 extra/test/test_hfrecord.py a.hfrec.json    # any records
    python3 extra/test/test_hfrecord.py --fits DIR      # and the Send to Python files in DIR

With no arguments it reads extra/test-classes/hfrecord/*.hfrec.json, which ProcessingRecordCheck
writes during the checks step of ant check-all, and fails when there are none, so a check that
stopped writing them cannot pass by validating nothing. Each record must pass the schema (draft
2020-12) and carry no em dash; a record claiming a format this schema does not describe must be
refused, which is the proof the validator can fail. Needs the jsonschema package.

It also reads the Send to Python files FitsRecordCheck wrote (extra/test-classes/hfrecord-fits/,
or --fits DIR) with astropy: each is valid FITS, its HFRECORD extension decodes the way HelioFITS Core's
reader does (src/heliofits_core/record.py, _fits_record: the 8-bit image's bytes as UTF-8) and passes the
schema with the record_id of its HFRECID card, its HISTORY record block is numbered in order with
every card's text within 72 characters, and hfrecord.json beside them passes the schema. Without
astropy that part is reported and passed over, except under CHECKS_STRICT=1, where it fails.
"""

from __future__ import annotations

import copy
import json
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = ROOT / "docs" / "spec" / "hfrecord-1.schema.json"
DEFAULT = ROOT / "extra" / "test-classes" / "hfrecord"
FITS_DEFAULT = ROOT / "extra" / "test-classes" / "hfrecord-fits"
PREFIX = "HFREC 1.0 "  # ProcessingRecord.FITS_HISTORY_PREFIX


def check_fits(folder: Path, validator) -> int:
    """Failures among the Send to Python files in folder (see the module docstring)."""
    files = sorted(folder.glob("*_phys.fits"))
    if not files:
        print(f"  FAIL no Send to Python files in {folder} (FitsRecordCheck writes them)")
        return 1
    try:
        from astropy.io import fits
    except ImportError:
        if os.environ.get("CHECKS_STRICT") == "1":
            print("  FAIL astropy not installed, so the FITS files were not read (CHECKS_STRICT=1)")
            return 1
        print("  skip the FITS files: astropy not installed (apt-get install python3-astropy)")
        return 0
    failures = 0

    def expect(what: str, ok: bool) -> None:
        nonlocal failures
        print(("  ok   " if ok else "  FAIL ") + what)
        failures += not ok

    for f in files:
        with fits.open(f, memmap=False) as hdus:
            try:
                hdus.verify("exception")
                valid = True
            except Exception as exc:  # verify raises VerifyError, and others for a damaged file
                valid = False
                print(f"       {exc}")
            expect(f"{f.name}: valid FITS (astropy verify)", valid)
            ext = [h for h in hdus if h.name.upper() == "HFRECORD"]
            record = None
            if ext and ext[0].data is not None:
                try:
                    record = json.loads(bytes(ext[0].data.astype("uint8").ravel().tobytes()).rstrip(b"\0").decode("utf-8"))
                except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                    print(f"       {exc}")
            expect(f"{f.name}: the HFRECORD extension decodes to JSON", isinstance(record, dict))
            errors = [] if record is None else list(validator.iter_errors(record))
            expect(f"{f.name}: the embedded record passes the schema"
                   + "".join(f"\n       {list(e.path)}: {e.message}" for e in errors[:10]), record is not None and not errors)
            header = hdus[0].header
            expect(f"{f.name}: HFRECID is the record's record_id",
                   record is not None and header.get("HFRECID") == record.get("record_id"))
            history = [str(v) for v in header.get("HISTORY", [])]
            block = [v.strip() for v in history if v.strip().startswith(PREFIX)]
            expect(f"{f.name}: the record block opens with the record card and closes with the sidecar card",
                   len(block) >= 2 and block[0] == PREFIX + "record " + str(header.get("HFRECID"))
                   and block[-1] == PREFIX + "sidecar " + f.name + ".hfrec.json")
            expect(f"{f.name}: every HISTORY card's text is within 72 characters", all(len(v) <= 72 for v in history))
            numbers = [int(m.group(1)) for v in block if (m := re.match(re.escape(PREFIX) + r"step (\d+) ", v))]
            expect(f"{f.name}: step cards numbered 1 to {len(numbers)} in order", numbers == list(range(1, len(numbers) + 1)) and numbers)
            expect(f"{f.name}: ORIGIN names the suite product", str(header.get("ORIGIN", "")).startswith("heliofits-studio "))
    folder_record = folder / "hfrecord.json"
    record = json.loads(folder_record.read_text(encoding="utf-8")) if folder_record.is_file() else None
    errors = [] if record is None else list(validator.iter_errors(record))
    expect("hfrecord.json is there and passes the schema", record is not None and not errors)
    return failures


def main(argv: list[str]) -> int:
    try:
        import jsonschema
    except ImportError:
        print("SKIP: python jsonschema not installed (pip install jsonschema; apt-get install python3-jsonschema)")
        return 0
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    jsonschema.Draft202012Validator.check_schema(schema)
    validator = jsonschema.Draft202012Validator(schema)
    fits_dir = FITS_DEFAULT if not argv else None
    if len(argv) >= 2 and argv[0] == "--fits":
        fits_dir, argv = Path(argv[1]), argv[2:]
    files = [Path(a) for a in argv] or sorted(DEFAULT.glob("*.hfrec.json"))
    if not files:
        print(f"  FAIL no records to validate in {DEFAULT} (ProcessingRecordCheck writes them)")
        return 1
    failures = 0
    for f in files:
        text = f.read_text(encoding="utf-8")
        record = json.loads(text)
        errors = sorted(validator.iter_errors(record), key=lambda e: list(e.path))
        ok = not errors and chr(0x2014) not in text
        print(("  ok   " if ok else "  FAIL ") + f.name + "".join(f"\n       {list(e.path)}: {e.message}" for e in errors[:10]))
        failures += not ok
    bad = copy.deepcopy(json.loads(files[0].read_text(encoding="utf-8")))
    bad["hfrecord"] = "2.0"
    refused = any(True for _ in validator.iter_errors(bad))
    print(("  ok   " if refused else "  FAIL ") + "a record claiming hfrecord 2.0 is refused")
    failures += not refused
    if fits_dir is not None:
        failures += check_fits(fits_dir, validator)
    print(f"hfrecord: {len(files)} record(s), {failures} failure(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
