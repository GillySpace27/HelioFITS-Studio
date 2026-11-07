#!/bin/bash
# A relocatable Python with HelioFITS Core installed, for the Windows and Linux packages
# (release/package-app.sh). The macOS app gets the same thing from the core's own
# app/tools/bundle_python.sh; this is its counterpart for the other two platforms.
#
#   HFCORE_SRC=<checkout of GillySpace27/heliad at the commit in release/fits-core.txt> \
#       release/bundle-core.sh windows|linux <output folder>
#
# The Python is a python-build-standalone "install_only" build, checked against the SHA-256 digest
# GitHub publishes for the asset. The core is installed from HFCORE_SRC (not editable) with the
# archives extra. Windows: <out>/python.exe. Linux: <out>/bin/python3. The same places
# FitsCoreIngest.studioCore looks.
set -euo pipefail
OS="${1:?usage: $0 windows|linux <output folder>}"
OUT="${2:?usage: $0 windows|linux <output folder>}"
SRC="${HFCORE_SRC:?HFCORE_SRC must name a checkout of the core at the commit in release/fits-core.txt}"
PY_MINOR="${PY_MINOR:-3.12}"
case "$OS" in
    windows) TRIPLE=x86_64-pc-windows-msvc; PY="$OUT/python.exe" ;;
    linux)   TRIPLE=x86_64-unknown-linux-gnu; PY="$OUT/bin/python3" ;;
    *) echo "usage: $0 windows|linux <output folder>" >&2; exit 2 ;;
esac
HOSTPY="$(command -v python3 || command -v python)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> python-build-standalone $PY_MINOR for $TRIPLE"
curl -fsSL https://api.github.com/repos/astral-sh/python-build-standalone/releases/latest -o "$WORK/release.json"
read -r NAME URL DIGEST < <("$HOSTPY" - "$WORK/release.json" "$PY_MINOR" "$TRIPLE" <<'PY'
import json, re, sys
release, minor, triple = json.load(open(sys.argv[1])), sys.argv[2], sys.argv[3]
pattern = re.compile(rf"^cpython-{re.escape(minor)}\.\d+\+\d+-{re.escape(triple)}-install_only\.tar\.gz$")
assets = [a for a in release["assets"] if pattern.match(a["name"])]
if len(assets) != 1:
    sys.exit(f"expected one matching asset in {release['tag_name']}, found {len(assets)}")
a = assets[0]
print(a["name"], a["browser_download_url"], a.get("digest") or "none")
PY
)
[ -n "${URL:-}" ] || { echo "!! no python-build-standalone asset found" >&2; exit 1; }
curl -fsSL "$URL" -o "$WORK/$NAME"
GOT="sha256:$(sha256sum "$WORK/$NAME" | cut -d' ' -f1)"
[ "$DIGEST" != none ] || { echo "!! GitHub published no digest for $NAME; refusing an unchecked download" >&2; exit 1; }
[ "$GOT" = "$DIGEST" ] || { echo "!! checksum mismatch for $NAME: got $GOT, expected $DIGEST" >&2; exit 1; }
echo "    checksum matches ($DIGEST)"

tar -xzf "$WORK/$NAME" -C "$WORK"
rm -rf "$OUT"
mkdir -p "$(dirname "$OUT")"
mv "$WORK/python" "$OUT"

SRC_ARG="$SRC"
[ "$OS" = windows ] && SRC_ARG="$(cygpath -w "$SRC")"
echo "==> installing heliofits[archives] from the core checkout"
# -s: pip would otherwise count packages in the build machine's user site as already installed.
"$PY" -s -m pip install --quiet --no-cache-dir --no-compile --disable-pip-version-check "$SRC_ARG[archives]"
find "$OUT" -name __pycache__ -type d -prune -exec rm -rf {} +

"$PY" -B -s -P -m heliofits_core --help 2>/dev/null | grep -q 'layer-upgrade' \
    || { echo "!! the bundled core has no heliofits_core with layer-upgrade: Use FITS would not work" >&2; exit 1; }
echo "==> HelioFITS Core in $OUT ($(du -sh "$OUT" | cut -f1))"
