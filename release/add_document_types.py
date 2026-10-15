#!/usr/bin/env python3
"""Declare the document types HelioFITS Studio opens in a built app's Info.plist (HS-16).

    add_document_types.py <path to Contents/Info.plist>

.jhv scenes: Editor, default handler. FITS images: Viewer at Alternate rank, so Finder keeps
HelioFITS as the default and Studio is an "Open With" choice. Idempotent: running it again changes
nothing. Runs before signing, since it edits a file inside the bundle.
"""
from __future__ import annotations

import plistlib
import sys

TYPES = [
    {"CFBundleTypeName": "HelioFITS Studio Scene", "CFBundleTypeExtensions": ["jhv"], "CFBundleTypeRole": "Editor"},
    {"CFBundleTypeName": "FITS Image", "CFBundleTypeExtensions": ["fits", "fts", "fit"], "CFBundleTypeRole": "Viewer",
     "LSHandlerRank": "Alternate"},
]


def add_document_types(info: dict) -> dict:
    have = info.setdefault("CFBundleDocumentTypes", [])
    names = {t.get("CFBundleTypeName") for t in have}
    have.extend(t for t in TYPES if t["CFBundleTypeName"] not in names)
    return info


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    with open(argv[1], "rb") as fh:
        info = plistlib.load(fh)
    add_document_types(info)
    with open(argv[1], "wb") as fh:
        plistlib.dump(info, fh)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
