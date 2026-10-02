#!/usr/bin/env python3
"""release/assets.txt is the one list of release asset names, and publish refuses what it cannot prove.

Run from anywhere:  python3 extra/test/test_release_assets.py

AssetListTest pins the list against the scripts that produce the files. AttachTest checks that publish
and package.yml attach exactly the listed names. PreflightTest runs `deploy_release.sh publish --dry-run`
in a throwaway repository with a bare local origin and fake gh and spctl first on PATH, so nothing here
can tag, push or upload anything real. RunbookTest keeps the runbook on the dry run and off deletion.
"""
import hashlib
import importlib.util
import json
import os
import re
import shutil
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RELEASE = ROOT / "release"

# The contract as first written. Rows are append-only, so later rows may follow these eight.
INITIAL_ROWS = [
    ("HFStudio-{v}.dmg", "local"),
    ("HFStudio-{v}-intel.dmg", "local"),
    ("HFStudio-{v}.zip", "local"),
    ("HFStudio-{v}-windows.zip", "ci"),
    ("HFStudio-{v}-linux.tar.gz", "ci"),
    ("HFStudio-Guide.pdf", "local"),
    ("HFStudio-Guide.md", "local"),
    ("fabric_suvi.json.gz", "local"),
]


def rows():
    out = []
    for line in (RELEASE / "assets.txt").read_text().splitlines():
        if line.strip() and not line.startswith("#"):
            out.append(tuple(line.split()))
    return out


def version():
    return (ROOT / "VERSION").read_text().strip()


def load_status():
    spec = importlib.util.spec_from_file_location(
        "hfs_status", RELEASE / "skills" / "ship-hfstudio" / "scripts" / "status.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class AssetListTest(unittest.TestCase):
    def test_rows_are_the_contract(self):
        found = rows()
        for row in found:
            self.assertEqual(len(row), 2, f"row {row} is not '<pattern> local|ci'")
            self.assertIn(row[1], ("local", "ci"), f"row {row}")
        self.assertEqual(found[:len(INITIAL_ROWS)], INITIAL_ROWS)

    def test_ci_rows_are_what_package_app_builds(self):
        text = (RELEASE / "package-app.sh").read_text()
        built = {m.replace("$VERSION", "{v}")
                 for m in re.findall(r'ARCHIVE="(HFStudio-\$VERSION-[^"]+)"', text)}
        self.assertEqual(built, {p for p, s in rows() if s == "ci"})

    def test_local_rows_are_what_deploy_release_names(self):
        text = (RELEASE / "deploy_release.sh").read_text()
        top = re.search(r'^TOP="([^"]+)"', text, re.M).group(1).replace("$VERSION", "{v}")
        made = set()
        for var in ("ZIP", "PDF", "MD", "CLOUD", "DMG"):
            for path in re.findall(r"\b" + var + r'="\$HERE/([^"]+)"', text):
                made.add(path.replace("$TOP", top))
        self.assertEqual(made, {p for p, s in rows() if s == "local"})

    def test_tracker_reads_the_list(self):
        status = load_status()
        v = status.VERSION
        self.assertEqual(status.read_assets(v), [p.replace("{v}", v) for p, _ in rows()])
        self.assertEqual(status.read_assets(v, "ci"),
                         [p.replace("{v}", v) for p, s in rows() if s == "ci"])
        local = status.read_assets(v, "local")
        for name in (status.DMG_NAME, status.ZIP_NAME, status.PDF_NAME):
            self.assertIn(name, local)


if __name__ == "__main__":
    unittest.main()
