#!/usr/bin/env python3
"""Repository guards for HelioFITS Studio. Standard library only, Python 3.11+.

  python3 extra/ci/guards.py diff [--base origin/master]
      Fails (exit 1) when the change since the merge base with <base>, committed or not:
        - adds an em dash (U+2014) outside extra/test/data/;
        - changes or removes a frozen HFStudio identifier (Main-Class, Directories.NAME, BUNDLE_ID);
        - brings a Kakadu file (*kdu*) back under lib/;
        - loses resources/licenses/FFmpeg-Notices.txt or resources/licenses/GPL-3.0.txt;
        - adds --clobber anywhere except inside upload_guide_only() in release/deploy_release.sh.
      Warns when lib/natives-macos/libjhvmetalhost.dylib changes (a build product; never commit it).

  python3 extra/ci/guards.py count [--update]
      Counts catch-alls, System.out/err prints, em dashes and non-final static fields in src/
      and fails when any count is above extra/ci/ratchet.json. --update writes the file when it is
      absent and otherwise only lowers counts; it never raises one.
"""

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
RATCHET = ROOT / "extra/ci/ratchet.json"
EM_DASH = "\u2014"
SELF = "extra/ci/guards.py"
FROZEN = {
    "build.xml": '<attribute name="Main-Class" value="org.helioviewer.jhv.HFStudio"/>',
    "src/org/helioviewer/jhv/io/Directories.java": 'private static final String NAME = "HFStudio";',
    "release/deploy_release.sh": 'BUNDLE_ID="space.gilly.hfstudio"',
}
REQUIRED = ("resources/licenses/FFmpeg-Notices.txt", "resources/licenses/GPL-3.0.txt")
DYLIB = "lib/natives-macos/libjhvmetalhost.dylib"
CLOBBER_FILES = (".sh", ".yml", ".yaml", ".py", ".ps1")
COUNTS = {
    "catch_all": re.compile(r"catch\s*\(\s*(?:final\s+)?(?:Exception|Throwable)\b"),
    "print": re.compile(r"System\.(?:out|err)\.print"),
    "em_dash": re.compile(EM_DASH),
    "non_final_static": re.compile(
        r"^\s*(?:(?:public|protected|private|volatile|transient)\s+)*static\s+"
        r"(?:(?:volatile|transient)\s+)*"
        r"(?!final\b|class\b|interface\b|enum\b|record\b|abstract\b|synchronized\b|native\b|void\b|<)"
        r"[\w.$<>\[\], ?]+?\s+\w+\s*(?:=|;)",
        re.M,
    ),
}


def git(*args):
    return subprocess.run(
        ["git", *args], cwd=ROOT, check=True, capture_output=True, text=True, encoding="utf-8", errors="replace"
    ).stdout


def added_lines(base):
    """{path: [(new line number, text)]} for the working tree against merge-base(base, HEAD)."""
    merge_base = git("merge-base", base, "HEAD").strip()
    added, path, number = {}, None, 0
    for line in git("diff", "-U0", "--no-color", "--no-ext-diff", merge_base).splitlines():
        if line.startswith("+++ "):
            path = line[6:] if line.startswith("+++ b/") else None
        elif line.startswith("@@"):
            number = int(re.match(r"@@ -\S+ \+(\d+)", line).group(1))
        elif line.startswith("+") and path:
            added.setdefault(path, []).append((number, line[1:]))
            number += 1
    for path in git("ls-files", "--others", "--exclude-standard").splitlines():
        try:
            text = (ROOT / path).read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        added[path] = list(enumerate(text.splitlines(), 1))
    changed = set(git("diff", "--name-only", merge_base).splitlines())
    return added, changed


def function_body(path, name):
    """Line numbers inside `name() {` ... first `}` at column 0, in the working-tree file."""
    lines = (ROOT / path).read_text(encoding="utf-8").splitlines()
    inside, body = False, set()
    for number, text in enumerate(lines, 1):
        if text.startswith(name + "()"):
            inside = True
        elif inside and text.startswith("}"):
            inside = False
        elif inside:
            body.add(number)
    return body


def diff_mode(base):
    failures, warnings = [], []
    try:
        added, changed = added_lines(base)
    except subprocess.CalledProcessError as error:
        print(f"FAIL base {base} not found ({error.stderr.strip()}); fetch it, e.g. fetch-depth: 0")
        return 1
    for path, lines in sorted(added.items()):
        if path == SELF:
            continue
        for number, text in lines:
            if EM_DASH in text and not path.startswith("extra/test/data/"):
                failures.append(f"{path}:{number}: adds an em dash (U+2014)")
        if path.endswith(CLOBBER_FILES):
            allowed = function_body(path, "upload_guide_only") if path == "release/deploy_release.sh" else set()
            for number, text in lines:
                code = text.strip()
                if "--clobber" in code and not code.startswith("#") and number not in allowed:
                    failures.append(f"{path}:{number}: adds --clobber outside upload_guide_only()")
    for path, frozen in FROZEN.items():
        target = ROOT / path
        if not target.is_file() or frozen not in [l.strip() for l in target.read_text(encoding="utf-8").splitlines()]:
            failures.append(f"{path}: frozen line changed or missing: {frozen}")
    for path in sorted((ROOT / "lib").rglob("*")):
        if "kdu" in path.name.lower():
            failures.append(f"{path.relative_to(ROOT).as_posix()}: Kakadu must not return")
    for path in REQUIRED:
        if not (ROOT / path).is_file():
            failures.append(f"{path}: missing (the About dialog links it)")
    if DYLIB in changed:
        warnings.append(f"{DYLIB} changed: it is a build product; do not commit it")
    for line in warnings:
        print("WARN " + line)
    for line in failures:
        print("FAIL " + line)
    print(f"guards diff: {len(failures)} failed, {len(warnings)} warnings (base {base})")
    return 1 if failures else 0


def counts():
    totals = dict.fromkeys(COUNTS, 0)
    for path in sorted((ROOT / "src").rglob("*.java")):
        text = path.read_text(encoding="utf-8", errors="replace")
        for key, pattern in COUNTS.items():
            totals[key] += len(pattern.findall(text))
    return totals


def count_mode(update):
    now = counts()
    if not RATCHET.is_file():
        if not update:
            print(f"FAIL {RATCHET.relative_to(ROOT)} missing; create it with: python3 {SELF} count --update")
            return 1
        RATCHET.write_text(json.dumps(now, indent=2) + "\n", encoding="utf-8")
        print("guards count: wrote " + json.dumps(now))
        return 0
    limits = json.loads(RATCHET.read_text(encoding="utf-8"))
    failures = [f"{key}: {now[key]} > {limits.get(key, 0)}" for key in COUNTS if now[key] > limits.get(key, 0)]
    for line in failures:
        print("FAIL " + line)
    if update and not failures:
        lowered = {key: min(now[key], limits.get(key, now[key])) for key in COUNTS}
        if lowered != limits:
            RATCHET.write_text(json.dumps(lowered, indent=2) + "\n", encoding="utf-8")
            print("guards count: lowered to " + json.dumps(lowered))
    print("guards count: " + ", ".join(f"{key} {now[key]}/{limits.get(key, 0)}" for key in COUNTS))
    return 1 if failures else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="mode", required=True)
    diff = sub.add_parser("diff")
    diff.add_argument("--base", default="origin/master")
    count = sub.add_parser("count")
    count.add_argument("--update", action="store_true")
    args = parser.parse_args()
    return diff_mode(args.base) if args.mode == "diff" else count_mode(args.update)


if __name__ == "__main__":
    sys.exit(main())
