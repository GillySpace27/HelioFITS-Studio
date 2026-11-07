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
MODULE = "heliofits_core"  # FitsCoreIngest.MODULE


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
    repo = os.environ.get("HFCORE_REPO") or os.environ.get("HELIAD_REPO") or "https://github.com/GillySpace27/heliad.git"

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

    for cache in list(out.rglob("__pycache__")):
        shutil.rmtree(cache, ignore_errors=True)
    # The same command Studio runs (FitsCoreIngest.command), so a core Use FITS cannot use fails here.
    run = subprocess.run([str(py), "-B", "-s", "-P", "-m", MODULE, "--help"], capture_output=True, text=True)
    if run.returncode or "layer-upgrade" not in run.stdout:
        die(f"the bundled core at {commit} has no {MODULE} with layer-upgrade: Use FITS would not work\n{run.stderr}")
    (out / "HFCORE_COMMIT").write_text(commit + "\n")
    size = sum(f.stat().st_size for f in out.rglob("*") if f.is_file())
    print(f"   HelioFITS Core {commit} in {out} ({size / 2**20:.0f} MB)")


if __name__ == "__main__":
    main()
