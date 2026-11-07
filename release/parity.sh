#!/bin/sh
# Decode-parity report for the HFStudio.jar at the repository root: release/HFStudio-<version>-parity.json.
# Needs the machine that holds the library and a Python with the core (pyheliofits, or heliofits_core before
# pyHelioFITS #44), sunpy and astropy:
#   HELIAD_DIR=~/pyHelioFITS HELIAD_PYTHON=~/pyHelioFITS/.venv/bin/python release/parity.sh [--library DIR] [--out FILE]
# See release/RELEASING.md step 5b and release/parity.py.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
exec "${HELIAD_PYTHON:-python3}" "$HERE/parity.py" run "$@"
