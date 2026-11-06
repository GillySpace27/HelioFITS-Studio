#!/usr/bin/env python3
"""Decode-parity report for a release jar, and the check publish makes on it.

    parity.py run    [--library DIR] [--out FILE]      build HFStudio-<version>-parity.json (needs heliad + sunpy)
    parity.py verify FILE --jar JAR --commit SHA --version V [--manifest FILE]   exit 1 unless FILE vouches for JAR

`run` decodes every pinned fixture in release/parity-manifest.json with the DecodeParityDump class
inside the release jar, then compares each dump with sunpy through heliad's heliofits_core.parity.
It needs the machine that holds the library, so it is a release step, not CI (docs/decode-parity.md).
`verify` is standard library only, because publish runs it with the system python3.
The tolerances are heliad's assumed ones, unchanged; the report carries the observed maxima beside them.
"""

import argparse
import hashlib
import json
import os
import platform
import subprocess
import sys
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
SRC = HERE.parent
FORMAT = "hf-parity-report/1"


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def variants(manifest):
    """(entry, plane, subtract_background) for everything the manifest asks to be run. A LASCO entry runs both ways."""
    for e in manifest["entries"]:
        for plane in e["planes"]:
            for bg in ([False, True] if e.get("background") else [False]):
                yield e, plane, bg


def key(entry_id, plane, bg):
    return f"{entry_id}:p{plane}" + (":bg" if bg else "")


def git(*args, cwd):
    return subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, check=True).stdout.strip()


def manifest_attr(jar, name):
    with zipfile.ZipFile(jar) as z:
        for line in z.read("META-INF/MANIFEST.MF").decode().splitlines():
            if line.startswith(name + ": "):
                return line.split(": ", 1)[1].strip()
    return ""


# ---------------------------------------------------------------------------------------------- verify

def verify(report_path, jar, commit, version, manifest_path):
    """Problems with the report as a voucher for this jar, as a list of strings (empty means it vouches)."""
    bad = []
    try:
        r = json.loads(Path(report_path).read_text())
    except (OSError, ValueError) as e:
        return [f"cannot read {report_path}: {e}"]
    if r.get("format") != FORMAT:
        bad.append(f"format is {r.get('format')!r}, not {FORMAT}")
    if r.get("studio", {}).get("version") != version:
        bad.append(f"report is for version {r.get('studio', {}).get('version')!r}, not {version}")
    if r.get("studio", {}).get("commit") != commit:
        bad.append(f"report is for commit {r.get('studio', {}).get('commit')!r}, not {commit}")
    if r.get("studio", {}).get("jar_sha256") != sha256_file(jar):
        bad.append("report was made from a different HFStudio.jar (sha256 differs)")
    if r.get("studio", {}).get("dirty") is not False:
        bad.append("report was made from a dirty Studio tree")
    if r.get("heliad", {}).get("dirty") is not False or not r.get("heliad", {}).get("commit"):
        bad.append("report does not name a clean heliad commit")
    manifest = json.loads(Path(manifest_path).read_text())
    if r.get("manifest", {}).get("sha256") != sha256_file(manifest_path):
        bad.append("report was made with a different parity-manifest.json")
    have = {e.get("key"): e for e in r.get("entries", [])}
    for e, plane, bg in variants(manifest):
        k = key(e["id"], plane, bg)
        got = have.get(k)
        if got is None:
            bad.append(f"{k}: not in the report")
        elif got.get("sha256") != e["sha256"]:
            bad.append(f"{k}: report names a different file")
        elif got.get("status") != "ok" or not got.get("checks") or not all(c.get("ok") is True for c in got["checks"]):
            failed = [c.get("name") for c in got.get("checks", []) if c.get("ok") is not True]
            bad.append(f"{k}: {got.get('status')}" + (f" ({', '.join(failed)})" if failed else ""))
    covered = {have[key(e['id'], p, b)].get("instrument") for e, p, b in variants(manifest)
               if key(e['id'], p, b) in have and have[key(e['id'], p, b)].get("status") == "ok"}
    for inst in manifest["required_instruments"]:
        if inst not in covered:
            bad.append(f"no passing fixture for {inst}")
    if r.get("passed") is not True and not bad:
        bad.append("report says passed = false")
    return bad


# ------------------------------------------------------------------------------------------------- run

def find_fixture(library, entry):
    p = Path(library) / entry["library_relpath"]
    if not p.is_file():
        return None, f"not found at {p}"
    got = sha256_file(p)
    if got != entry["sha256"]:
        return None, f"sha256 is {got}, the manifest pins {entry['sha256']}"
    return p, ""


def dump_one(jar, libs, src, plane, bg, outdir):
    """Run the release jar's DecodeParityDump on one file; return the path of the dump json."""
    classpath = os.pathsep.join([str(jar), *libs])
    cmd = ["java", "--enable-native-access=ALL-UNNAMED", "--add-exports=java.desktop/sun.awt=ALL-UNNAMED", "--add-exports=java.desktop/sun.swing=ALL-UNNAMED", f"-Duser.home={tempfile.mkdtemp(prefix='hfs-parity-home-')}",
           "-Djava.awt.headless=true", "-cp", classpath, "org.helioviewer.jhv.view.uri.DecodeParityDump",
           str(outdir), str(src), "--plane", str(plane)] + (["--subtract-background"] if bg else [])
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=900)
    dumps = sorted(Path(outdir).glob(f"{src.name}.p{plane}.hfdump.json"), key=os.path.getmtime)
    if r.returncode != 0 or not dumps:
        raise RuntimeError("dump failed: " + (r.stderr or r.stdout)[-600:])
    return dumps[-1]


def run(library, out):
    from astropy import __version__ as astropy_version
    import numpy
    import sunpy
    from heliofits_core import parity

    jar = SRC / "HFStudio.jar"
    if not jar.is_file():
        sys.exit("no HFStudio.jar at the repository root; build it first (RELEASING.md step 2)")
    heliad_dir = os.environ.get("HELIAD_DIR") or str(Path(parity.__file__).resolve().parents[2])
    version = (SRC / "VERSION").read_text().strip()
    manifest_path = HERE / "parity-manifest.json"
    manifest = json.loads(manifest_path.read_text())
    libs = [str(p) for p in sorted((SRC / "lib").rglob("*.jar"))]
    entries, observed = [], {"data_max_abs_difference": {}, "wcs_grid_arcsec": 0.0, "time_ms": 0.0}
    with tempfile.TemporaryDirectory(prefix="hfs-parity-") as tmp:
        for e, plane, bg in variants(manifest):
            row = {"key": key(e["id"], plane, bg), "id": e["id"], "instrument": e["instrument"], "name": e["name"],
                   "sha256": e["sha256"], "size": e["size"], "plane": plane, "subtract_background": bg,
                   "status": "error", "checks": []}
            entries.append(row)
            src, why = find_fixture(library, e)
            if src is None:
                row["status"], row["detail"] = "missing", why
                continue
            try:
                outdir = Path(tmp) / row["key"].replace(":", "_")
                outdir.mkdir()
                dump = parity.read_dump(dump_one(jar, libs, src, plane, bg, outdir))
                ref = parity.reference_dump(src, dump.plane, like=dump)
                rep = parity.compare_dumps(dump, ref)
            except Exception as exc:  # one fixture failing must not hide the others
                row["detail"] = f"{type(exc).__name__}: {exc}"[:600]
                continue
            row["checks"] = [c.as_dict() for c in rep.checks]
            if bg and not any(c["name"] == "unit" and c["value"] == "DN/s" for c in row["checks"]):
                row["checks"].append({"name": "background_subtracted", "ok": False, "value": "no",
                                      "tolerance": "yes", "basis": "--subtract-background was asked for", "detail": ""})
            row["status"] = "ok" if all(c["ok"] for c in row["checks"]) else "fail"
            for c in row["checks"]:
                if c["name"] == "data" and isinstance(c["value"], dict):
                    d = observed["data_max_abs_difference"]
                    d[e["instrument"]] = max(d.get(e["instrument"], 0.0), c["value"]["max_abs_difference"])
                elif c["name"] == "wcs_grid_arcsec" and isinstance(c["value"], (int, float)):
                    observed["wcs_grid_arcsec"] = max(observed["wcs_grid_arcsec"], c["value"])
                elif c["name"] == "time" and isinstance(c["value"], (int, float)):
                    observed["time_ms"] = max(observed["time_ms"], c["value"])
    head = git("rev-parse", "HEAD", cwd=SRC)
    report = {
        "format": FORMAT,
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "claim": ("For each pinned file, the decoded values, observation time and pixel-to-sky grid that this release "
                  "jar produces equal what sunpy.map.Map gives, plus the named corrections of docs/decode-parity.md in "
                  "heliad. Not covered: the display path, the observer position, other files."),
        "studio": {"version": version, "commit": head, "jar_commit": manifest_attr(jar, "commit"),
                   "jar_revision": manifest_attr(jar, "revision"), "jar_sha256": sha256_file(jar),
                   "dirty": bool(git("status", "--porcelain", "src", "resources", "VERSION", cwd=SRC))},
        "heliad": {"commit": git("rev-parse", "HEAD", cwd=heliad_dir),
                   "branch": git("rev-parse", "--abbrev-ref", "HEAD", cwd=heliad_dir),
                   "dirty": bool(git("status", "--porcelain", "src", "tools", "tests", cwd=heliad_dir))},
        "tools": {"python": platform.python_version(), "sunpy": sunpy.__version__, "astropy": astropy_version,
                  "numpy": numpy.__version__, "java": subprocess.run(["java", "-version"], capture_output=True,
                                                                     text=True).stderr.splitlines()[0]},
        "manifest": {"path": "release/parity-manifest.json", "sha256": sha256_file(manifest_path),
                     "required_instruments": manifest["required_instruments"], "unavailable": manifest["unavailable"]},
        "assumed_tolerances": {"wcs_grid_arcsec": parity.WCS_TOL_ARCSEC, "time_ms": parity.TIME_TOL_MS,
                               "data_rtol_float": parity.DATA_RTOL_FLOAT,
                               "basis": "heliad docs/decode-parity.md, 'Tolerances and their basis'; unchanged"},
        "observed_maxima": observed,
        "entries": entries,
    }
    report["passed"] = not verify_entries(report, manifest)
    Path(out).write_text(json.dumps(report, indent=1) + "\n")
    print(f"{'PASS' if report['passed'] else 'FAIL'}: {sum(e['status'] == 'ok' for e in entries)} of {len(entries)} "
          f"fixture runs ok -> {out}")
    for e in entries:
        if e["status"] != "ok":
            print(f"  {e['key']}: {e['status']} {e.get('detail', '')[:200]}")
    return 0 if report["passed"] else 1


def verify_entries(report, manifest):
    have = {e["key"]: e for e in report["entries"]}
    bad = [k for e, p, b in variants(manifest) if (k := key(e["id"], p, b)) not in have or have[k]["status"] != "ok"]
    covered = {have[k]["instrument"] for e, p, b in variants(manifest) if (k := key(e["id"], p, b)) in have and have[k]["status"] == "ok"}
    return bad + [i for i in manifest["required_instruments"] if i not in covered]


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--library", default=os.environ.get("HFPARITY_LIBRARY", str(Path.home() / "HeliadLibrary")))
    r.add_argument("--out")
    v = sub.add_parser("verify")
    v.add_argument("report")
    v.add_argument("--jar", required=True)
    v.add_argument("--commit", required=True)
    v.add_argument("--version", required=True)
    v.add_argument("--manifest", default=str(HERE / "parity-manifest.json"))
    ns = ap.parse_args(argv)
    if ns.cmd == "run":
        version = (SRC / "VERSION").read_text().strip()
        return run(ns.library, ns.out or str(HERE / f"HFStudio-{version}-parity.json"))
    bad = verify(ns.report, ns.jar, ns.commit, ns.version, ns.manifest)
    for b in bad:
        print(f"parity: {b}", file=sys.stderr)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
