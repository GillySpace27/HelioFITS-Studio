#!/usr/bin/env python3
"""Put HelioFITS Core into a Windows or Linux package, as deploy_release.sh does for the Mac app.

    python3 release/bundle_core.py windows pkg-out/HFStudio/heliofits-core
    python3 release/bundle_core.py linux   pkg-out/HFStudio/lib/heliofits-core

The core is the commit release/fits-core.txt pins, cloned from HFCORE_REPO (default: the GitHub
repository), installed with its archives extra into a python-build-standalone "install_only"
Python for the target. The Python download is checked against the SHA-256 digest GitHub publishes
for the asset and refused without one. Studio finds the result with FitsCoreIngest.studioCore.

Runs on the system it packages for (the CI runner), like jpackage. Standard library only.
"""
import hashlib
import json
import platform
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
PY_MINOR = os.environ.get("PY_MINOR", "3.12")
TRIPLES = {"windows": "x86_64-pc-windows-msvc", "linux": "x86_64-unknown-linux-gnu"}
ARM_TRIPLES = {"linux": "aarch64-unknown-linux-gnu"}
RELEASES = "https://api.github.com/repos/astral-sh/python-build-standalone/releases/latest"
# The module Studio runs the core as (FitsCoreIngest.moduleOf): pyheliofits.core from pyHelioFITS #44 on,
# heliofits_core before it. Whichever the pinned commit has is written to HFCORE_MODULE in the bundle.
MODULES = ("pyheliofits.core", "heliofits_core")


def die(msg):
    sys.exit(f"!! {msg}")


def pin():
    for line in (HERE / "fits-core.txt").read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            if not re.fullmatch(r"[0-9a-f]{40}", line):
                die(f"release/fits-core.txt must give one full 40-character commit hash, got '{line}'")
            return line
    die("release/fits-core.txt has no commit")


def fetch(url, dest=None):
    req = urllib.request.Request(url, headers={"User-Agent": "hfstudio-bundle-core"})
    token = os.environ.get("GITHUB_TOKEN")
    if token and url.startswith("https://api.github.com/"):
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=300) as r:
        if dest is None:
            return r.read()
        with open(dest, "wb") as f:
            shutil.copyfileobj(r, f)


def python_asset(triple):
    release = json.loads(fetch(RELEASES))
    pattern = re.compile(rf"^cpython-{re.escape(PY_MINOR)}\.\d+\+\d+-{re.escape(triple)}-install_only\.tar\.gz$")
    assets = [a for a in release["assets"] if pattern.match(a["name"])]
    if len(assets) != 1:
        die(f"expected one {triple} install_only asset in {release['tag_name']}, found {len(assets)}")
    a = assets[0]
    if not a.get("digest"):
        die(f"GitHub published no digest for {a['name']}; refusing an unchecked download")
    return a["name"], a["browser_download_url"], a["digest"]


def python_in(root, target):
    return root / "python.exe" if target == "windows" else root / "bin" / "python3"


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in TRIPLES:
        sys.exit("usage: bundle_core.py windows|linux <output folder>")
    target, out = sys.argv[1], Path(sys.argv[2]).resolve()
    commit = pin()
    repo = os.environ.get("HFCORE_REPO") or os.environ.get("HELIAD_REPO") or "https://github.com/GillySpace27/pyHelioFITS.git"

    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        src = work / "core"
        if subprocess.run(["git", "clone", "--quiet", "--filter=blob:none", repo, str(src)]).returncode:
            die(f"could not clone HelioFITS Core from {repo} (set HFCORE_REPO to a local clone)")
        if subprocess.run(["git", "-C", str(src), "-c", "advice.detachedHead=false", "checkout", "--quiet", commit]).returncode:
            die(f"HelioFITS Core commit {commit} is not in {repo}")

        arm = platform.machine().lower() in ("aarch64", "arm64")
        if arm and target not in ARM_TRIPLES:
            die(f"no {target} ARM64 Python is set up here")
        name, url, digest = python_asset((ARM_TRIPLES if arm else TRIPLES)[target])
        print(f"   Python: {name}")
        archive = work / name
        fetch(url, archive)
        got = "sha256:" + hashlib.sha256(archive.read_bytes()).hexdigest()
        if got != digest:
            die(f"checksum mismatch for {name}: got {got}, expected {digest}")
        with tarfile.open(archive) as tar:
            tar.extractall(work, filter="data")
        if out.exists():
            shutil.rmtree(out)
        out.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(work / "python"), str(out))

        py = python_in(out, target)
        # -s: packages in the build machine's user site would count as installed and be left out.
        if subprocess.run([str(py), "-s", "-m", "pip", "install", "--quiet", "--no-cache-dir", "--no-compile",
                           "--disable-pip-version-check", f"{src}[archives]"]).returncode:
            die("installing HelioFITS Core failed")
        # The trim the Mac bundle gets too (pyHelioFITS app/tools/trim_python.py, from #43 on): test
        # suites, pip, IDLE, headers. Older pins have no script and ship untrimmed.
        trim = Path(src) / "app" / "tools" / "trim_python.py"
        if trim.is_file() and subprocess.run([sys.executable, str(trim), str(out)]).returncode:
            die("trimming the bundled Python failed")

    relocate_scripts(out, target)
    for cache in list(out.rglob("__pycache__")):
        shutil.rmtree(cache, ignore_errors=True)
    # The same command Studio runs (FitsCoreIngest.command), so a core Use FITS cannot use fails here.
    module = None
    errors = []
    for name in MODULES:
        run = subprocess.run([str(py), "-B", "-s", "-P", "-m", name, "--help"], capture_output=True, text=True)
        if run.returncode == 0 and "layer-upgrade" in run.stdout:
            module = name
            break
        errors.append(f"-m {name}: exit {run.returncode}\n{run.stderr.strip()}")
    if module is None:
        die(f"the bundled core at {commit} has none of {MODULES} with layer-upgrade: Use FITS would not work\n" + "\n".join(errors))
    (out / "HFCORE_COMMIT").write_text(commit + "\n")
    (out / "HFCORE_MODULE").write_text(module + "\n")  # FitsCoreIngest.MODULE_MARK
    size = sum(f.stat().st_size for f in out.rglob("*") if f.is_file())
    print(f"   HelioFITS Core {commit} (-m {module}) in {out} ({size / 2**20:.0f} MB)")


# sh runs line 2 and never reads on; Python reads lines 2 and 3 as two adjacent string literals.
RELOCATABLE_HEAD = (b"#!/bin/sh\n"
                    b"'''exec' \"$(dirname -- \"$(realpath -- \"$0\")\")/python3\" \"$0\" \"$@\"\n"
                    b"' '''\n")


def relocate_scripts(out, target):
    """pip writes the build machine's absolute Python path into each console script's first line, so
    hfcore and heliofits-mcp ran only on the machine that built the package (0.9.2 Linux: "cannot
    execute: required file not found"). Point each at the python3 beside it instead. Windows
    launchers are .exe files and are left alone."""
    if target == "windows":
        return 0
    bindir = out / "bin"
    done = 0
    for script in sorted(bindir.iterdir()) if bindir.is_dir() else ():
        if script.is_symlink() or not script.is_file():
            continue
        with script.open("rb") as f:
            if f.read(2) != b"#!":
                continue
            first, rest = f.readline(), f.read()
        if b"python" not in first or b"/env " in first:
            continue
        script.write_bytes(RELOCATABLE_HEAD + rest)
        done += 1
    print(f"   {done} console scripts now find the bundled Python by their own location")
    return done


if __name__ == "__main__":
    main()
