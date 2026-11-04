#!/usr/bin/env python3
"""Helpers for the HFS_DEBUG_WARP diagnostic (.github/workflows/helioradial-diag.yml).

    helioradial_diag.py sessions BASE.jhv OUTDIR
        Writes OUTDIR/<Mode>.jhv for HPC, Helioradial and HelioradialUnrolled: BASE with every
        "projection" value that names a projection replaced by that mode.
    helioradial_diag.py summary PNG...
        One line per picture: pixels that are not black, and of those the share that is the
        stage colours (green for stage 1, magenta for a NaN), and the mean colour of the rest.

Needs Pillow for summary. Exit 0 on success, 2 on bad arguments.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

MODES = ("Orthographic", "HPC", "Helioradial", "HelioradialUnrolled", "Latitudinal", "ObserverSky")
TARGETS = ("HPC", "Helioradial", "HelioradialUnrolled")


def set_projection(node, mode: str) -> int:
    count = 0
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "projection" and value in MODES:
                node[key] = mode
                count += 1
            else:
                count += set_projection(value, mode)
    elif isinstance(node, list):
        for item in node:
            count += set_projection(item, mode)
    return count


def sessions(base: Path, outdir: Path) -> int:
    scene = json.loads(base.read_text(encoding="utf-8"))
    outdir.mkdir(parents=True, exist_ok=True)
    for mode in TARGETS:
        copy = json.loads(json.dumps(scene))
        n = set_projection(copy, mode)
        if n == 0:
            print(f"no projection key in {base}", file=sys.stderr)
            return 1
        (outdir / f"{mode}.jhv").write_text(json.dumps(copy), encoding="utf-8")
        print(f"{mode}.jhv: {n} projection value(s) set")
    return 0


def summary(paths: list[str]) -> int:
    from PIL import Image

    for p in paths:
        try:
            img = Image.open(p).convert("RGB")
        except OSError as e:
            print(f"{Path(p).name}: unreadable ({e})")
            continue
        drawn = green = magenta = 0
        rest = [0, 0, 0]
        for r, g, b in img.getdata():
            if r + g + b < 24:
                continue
            drawn += 1
            if g > 200 and r < 60 and b < 60:
                green += 1
            elif r > 200 and b > 200 and g < 60:
                magenta += 1
            else:
                rest[0] += r
                rest[1] += g
                rest[2] += b
        other = drawn - green - magenta
        mean = tuple(round(c / other) for c in rest) if other else (0, 0, 0)
        print(f"{Path(p).name}: {img.width}x{img.height} drawn={drawn} green={green} magenta={magenta} other={other} mean_other_rgb={mean}")
    return 0


def main(argv: list[str]) -> int:
    if len(argv) == 4 and argv[1] == "sessions":
        return sessions(Path(argv[2]), Path(argv[3]))
    if len(argv) >= 3 and argv[1] == "summary":
        return summary(argv[2:])
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
