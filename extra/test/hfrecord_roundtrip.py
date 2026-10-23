#!/usr/bin/env python3
"""Rebuild an exported still from its processing record, the test design.md asks for.

    hfrecord_roundtrip.py prepare EXPORT.png HOME STATE.jhv
        EXPORT.png.hfrec.json must exist, pass the schema (test_hfrecord.py), and its output.sha256
        must be the file's. Writes the record's native.studio_session scene, with "~" put back as
        HOME (the way Provenance.scene does), as a session file for -state.
    hfrecord_roundtrip.py compare FIRST.png SECOND.png
        Both records describe the same scene (same layers, sources and steps), and the pictures
        agree within a tolerance: same size, mean absolute difference at most 1 of 255 and at most
        1% of pixels off by more than 8 of 255 (estimates, set so a GPU or driver difference
        passes and a missing layer, a different colour table or a different frame does not).
        Identical bytes are not expected.

Needs Pillow and jsonschema. Exit 0 on pass, 1 on a failure, 2 on bad arguments.
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import test_hfrecord  # noqa: E402

HOME_REF = re.compile(r"^(file:/*)?~(?=[/\\]|$)")


def record_of(png: Path) -> dict:
    return json.loads(Path(str(png) + ".hfrec.json").read_text(encoding="utf-8"))


def expand(node, home: str):
    if isinstance(node, dict):
        return {k: expand(v, home) for k, v in node.items()}
    if isinstance(node, list):
        return [expand(v, home) for v in node]
    if isinstance(node, str):
        m = HOME_REF.match(node)
        if m:
            prefix = m.group(1) or ""
            return prefix + (home.replace("\\", "/") if prefix else home) + node[m.end():]
    return node


def fail(what: str) -> int:
    print(f"  FAIL {what}")
    return 1


def prepare(png: Path, home: str, state: Path) -> int:
    side = Path(str(png) + ".hfrec.json")
    if not side.is_file():
        return fail(f"no sidecar {side.name}")
    if test_hfrecord.main([str(side)]) != 0:
        return fail("the sidecar does not pass the schema")
    record = record_of(png)
    digest = hashlib.sha256(png.read_bytes()).hexdigest()
    if record["output"].get("sha256") != digest:
        return fail(f"output.sha256 {record['output'].get('sha256')} is not the file's {digest}")
    print("  ok   output.sha256 is the file's")
    if record["output"].get("file_name") != png.name:
        return fail(f"output.file_name {record['output'].get('file_name')} is not {png.name}")
    print("  ok   output.file_name is the file's")
    scene = record.get("native", {}).get("studio_session", {}).get("scene")
    if not isinstance(scene, dict):
        return fail("no native.studio_session.scene to rebuild from")
    state.write_text(json.dumps(expand(scene, home)), encoding="utf-8")
    print(f"  ok   wrote {state.name} from the record's native part")
    return 0


def same_scene(a: dict, b: dict) -> list[str]:
    problems = []
    la, lb = a["layers"], b["layers"]
    if len(la) != len(lb):
        return [f"{len(la)} layers, then {len(lb)}"]
    for x, y in zip(la, lb):
        if [s.get("sha256") for s in x["sources"]] != [s.get("sha256") for s in y["sources"]]:
            problems.append(f"{x['name']}: different sources")
        sx = [(s["op"], json.dumps(s["params"], sort_keys=True)) for s in x["steps"]]
        sy = [(s["op"], json.dumps(s["params"], sort_keys=True)) for s in y["steps"]]
        if sx != sy:
            problems.append(f"{x['name']}: different steps")
    return problems


def compare(first: Path, second: Path) -> int:
    from PIL import Image, ImageChops, ImageStat

    failures = 0
    problems = same_scene(record_of(first), record_of(second))
    print(("  ok   " if not problems else "  FAIL ") + "the two records describe the same scene" + "".join("; " + p for p in problems))
    failures += bool(problems)
    a, b = Image.open(first).convert("RGB"), Image.open(second).convert("RGB")
    if a.size != b.size:
        return fail(f"sizes differ: {a.size} and {b.size}")
    diff = ImageChops.difference(a, b)
    mean = sum(ImageStat.Stat(diff).mean) / 3
    grey = diff.convert("L")
    off = sum(grey.histogram()[9:]) / (a.size[0] * a.size[1])
    ok = mean <= 1.0 and off <= 0.01
    print(("  ok   " if ok else "  FAIL ") + f"pictures agree: mean |difference| {mean:.3f} of 255, {100 * off:.3f}% of pixels off by more than 8")
    failures += not ok
    return 1 if failures else 0


def main(argv: list[str]) -> int:
    if len(argv) == 4 and argv[0] == "prepare":
        return prepare(Path(argv[1]), argv[2], Path(argv[3]))
    if len(argv) == 3 and argv[0] == "compare":
        return compare(Path(argv[1]), Path(argv[2]))
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
