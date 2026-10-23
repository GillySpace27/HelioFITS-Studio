#!/usr/bin/env python3
"""Validate processing records (hfrecord 1.0) against docs/spec/hfrecord-1.schema.json.

    python3 extra/test/test_hfrecord.py                 # the records ProcessingRecordCheck wrote
    python3 extra/test/test_hfrecord.py a.hfrec.json    # any records

With no arguments it reads extra/test-classes/hfrecord/*.hfrec.json, which ProcessingRecordCheck
writes during the checks step of ant check-all, and fails when there are none, so a check that
stopped writing them cannot pass by validating nothing. Each record must pass the schema (draft
2020-12) and carry no em dash; a record claiming a format this schema does not describe must be
refused, which is the proof the validator can fail. Needs the jsonschema package.
"""

from __future__ import annotations

import copy
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = ROOT / "docs" / "spec" / "hfrecord-1.schema.json"
DEFAULT = ROOT / "extra" / "test-classes" / "hfrecord"


def main(argv: list[str]) -> int:
    try:
        import jsonschema
    except ImportError:
        print("SKIP: python jsonschema not installed (pip install jsonschema; apt-get install python3-jsonschema)")
        return 0
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    jsonschema.Draft202012Validator.check_schema(schema)
    validator = jsonschema.Draft202012Validator(schema)
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
    print(f"hfrecord: {len(files)} record(s), {failures} failure(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
