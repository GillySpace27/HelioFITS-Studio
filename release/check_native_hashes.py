#!/usr/bin/env python3
"""Check that every native library in a jar still matches the SHA-1 its jar records for it.

LWJGL's natives jars carry META-INF/<entry>.sha1 beside each library. At load, LWJGL hashes the
library it extracted and prints "[LWJGL] [ERROR] Incompatible Java and native library versions
detected." when the two differ. Code signing rewrites a Mach-O, so sign_jar_natives in
deploy_release.sh restamps the .sha1 after it signs; this script proves it did. Read-only.

    python3 release/check_native_hashes.py "<path>/HelioFITS Studio.app"   # or any folder or jar

Exit 0 when every recorded hash matches, 1 on any mismatch or when no recorded hash is found
(a wrong path must not pass), 2 on a usage error.
"""
import hashlib
import sys
import zipfile
from pathlib import Path


def mismatches(jar):
    """(entry, recorded, actual) for each library in jar whose META-INF/<entry>.sha1 disagrees."""
    out, checked = [], 0
    with zipfile.ZipFile(jar) as z:
        names = set(z.namelist())
        for n in sorted(names):
            if not (n.startswith("META-INF/") and n.endswith(".sha1")):
                continue
            entry = n[len("META-INF/"):-len(".sha1")]
            if entry not in names:
                continue
            checked += 1
            recorded = z.read(n).decode("ascii", "replace").strip()[:40].lower()
            actual = hashlib.sha1(z.read(entry)).hexdigest()
            if recorded != actual:
                out.append((entry, recorded, actual))
    return out, checked


def main(argv):
    if len(argv) != 2 or not Path(argv[1]).exists():
        print("usage: check_native_hashes.py <app, folder or jar>", file=sys.stderr)
        return 2
    root = Path(argv[1])
    jars = [root] if root.is_file() else sorted(root.rglob("*.jar"))
    bad, total = 0, 0
    for j in jars:
        found, checked = mismatches(j)
        total += checked
        for entry, recorded, actual in found:
            bad += 1
            print(f"  FAIL {j.name}!{entry}: jar records {recorded}, library is {actual}")
    if total == 0:
        print(f"!! no recorded native-library hash under {root}", file=sys.stderr)
        return 1
    print(f"native hashes: {total - bad} of {total} match")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
