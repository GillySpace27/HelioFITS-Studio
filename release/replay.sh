#!/bin/sh
# Replay gate for a release: the golden set of HelioFITS Core (pyHelioFITS tests/golden/, four records with
# their pictures) must still reproduce its pixel digests with the Core commit that release/fits-core.txt pins,
# which is the Core the packages carry. The pinned commit's src/ and tests/ are read out of a pyHelioFITS
# checkout with git archive into a temporary folder, so the checkout itself is never switched or changed.
#   HELIAD_DIR=~/pyHelioFITS HELIAD_PYTHON=~/pyHelioFITS/.venv/bin/python release/replay.sh
# Needs pytest and numpy in that Python. Offline. Exit 0 only when every golden test passes.
#
# Live layer (optional, needs a display): set HFS_REPLAY_RECORDS to real .hfrec files (separated by ':') and
# each is replayed in an isolated Studio started from the HFStudio.jar at the repository root (or from
# HFS_REPLAY_LAUNCH, with {home} standing for the throwaway home). Without records that layer is skipped.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
PIN="$(grep -v '^#' "$HERE/fits-core.txt" | grep -m1 .)"
DIR="${HELIAD_DIR:-$HOME/Documents/NWRA/heliad}"
PY="${HELIAD_PYTHON:-$DIR/.venv/bin/python}"
echo "$PIN" | grep -Eq '^[0-9a-f]{40}$' || { echo "replay: release/fits-core.txt names no full commit hash" >&2; exit 2; }
git -C "$DIR" cat-file -e "$PIN^{commit}" 2>/dev/null \
    || { echo "replay: the pyHelioFITS checkout at $DIR does not hold the pinned Core $PIN (set HELIAD_DIR, or git fetch there)" >&2; exit 2; }
git -C "$DIR" cat-file -e "$PIN:tests/test_golden_replay.py" 2>/dev/null \
    || { echo "replay: the pinned Core $PIN has no golden set (tests/test_golden_replay.py); pin pyHelioFITS 10892f0 or later" >&2; exit 2; }
"$PY" -c 'import pytest, numpy' 2>/dev/null || { echo "replay: $PY needs pytest and numpy (set HELIAD_PYTHON)" >&2; exit 2; }
WORK="$(mktemp -d "${TMPDIR:-/tmp}/hfs-replay.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
git -C "$DIR" archive "$PIN" src tests | tar -x -C "$WORK"
if [ -n "${HFS_REPLAY_RECORDS:-}" ] && [ -z "${HFS_REPLAY_LAUNCH:-}" ]; then
    [ -f "$HERE/../HFStudio.jar" ] || { echo "replay: HFS_REPLAY_RECORDS is set but there is no HFStudio.jar to start (ant jar, or set HFS_REPLAY_LAUNCH)" >&2; exit 2; }
    HFS_REPLAY_LAUNCH="java -Duser.home={home} -jar $HERE/../HFStudio.jar"
    export HFS_REPLAY_LAUNCH
fi
echo "replay: golden set of pyHelioFITS $PIN"
cd "$WORK"
PYTHONPATH="$WORK/src" "$PY" -m pytest -q -p no:cacheprovider tests/test_golden_replay.py
