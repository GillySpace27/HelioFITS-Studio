#!/usr/bin/env python3
"""Rewrite the cask's version and its two dmg checksums from a release's notes.

usage: bump_cask.py CASK_FILE VERSION   (needs gh; reads the "sha256  hex  name" lines)
"""
import re, subprocess, sys

cask, version = sys.argv[1], sys.argv[2]
body = subprocess.run(["gh", "release", "view", f"v{version}", "--repo", "GillySpace27/HelioFITS-Studio",
                       "--json", "body", "--jq", ".body"], check=True, capture_output=True, text=True).stdout
sha = {m[1]: m[0] for m in re.findall(r"(?m)^sha256\s+([0-9a-f]{64})\s+(\S+)$", body)}
arm, intel = f"HFStudio-{version}.dmg", f"HFStudio-{version}-intel.dmg"
for n in (arm, intel):
    if n not in sha:
        sys.exit(f"bump_cask: no sha256 line for {n} in the v{version} notes")
s = open(cask).read()
s, n1 = re.subn(r'(?m)^(  version ")[^"]+(")', rf"\g<1>{version}\2", s)
s, n2 = re.subn(r'(on_arm do\n    sha256 ")[0-9a-f]{64}', rf"\g<1>{sha[arm]}", s)
s, n3 = re.subn(r'(on_intel do\n    sha256 ")[0-9a-f]{64}', rf"\g<1>{sha[intel]}", s)
if (n1, n2, n3) != (1, 1, 1):
    sys.exit(f"bump_cask: cask layout changed (matched {n1},{n2},{n3} of 1,1,1)")
open(cask, "w").write(s)
