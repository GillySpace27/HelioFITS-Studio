#!/bin/sh
# Capture the guide figures that a tracked state file can reproduce, for Gilly to review.
#
#   release/capture_guide_shots.sh [out-dir]      (after `ant jar`; default out-dir $TMPDIR/hfs-guide-shots)
#
# For each release/guide_states/<figure>.jhv: copy it into a scratch folder with @SRC@ replaced by
# this checkout's path, start the app on the COPY with -state and a scratch home, photograph it with
# extra/launch-shot.sh, and leave <out-dir>/<figure>.png. The tracked file is never handed to the app:
# a file given to -state is adopted as the session (CommandLine.java, adoptSessionFile), so autosave
# and quit would write back into it. Nothing is copied into guide_assets/; a picture goes there only
# after Gilly has looked at it.
#
# Linux: run under xvfb-run (.github/workflows/guide.yml does). macOS: launch-shot.sh photographs the
# whole screen, so this refuses unless HFS_CAPTURE_ON_MAC=1 is set for the run.
set -eu
SRC="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-${TMPDIR:-/tmp}/hfs-guide-shots}"
STATES="$SRC/release/guide_states"

if [ "$(uname -s)" = Darwin ] && [ "${HFS_CAPTURE_ON_MAC:-}" != 1 ]; then
    echo "!! on macOS this photographs the whole screen; set HFS_CAPTURE_ON_MAC=1 for one run if that is fine" >&2
    exit 2
fi
case "$SRC" in
    *" "*|*"#"*) echo "!! the checkout path '$SRC' has a space or '#', which cannot go into a file: URI as is" >&2; exit 2 ;;
esac
[ -f "$SRC/HFStudio.jar" ] || { echo "!! no HFStudio.jar: run ant jar first" >&2; exit 1; }
set -- "$STATES"/*.jhv
[ -f "$1" ] || { echo "!! no state files in $STATES" >&2; exit 1; }

mkdir -p "$OUT"
SCRATCH="$(mktemp -d "${TMPDIR:-/tmp}/hfs-guide-XXXXXX")"
failed=0
for state in "$@"; do
    fig="$(basename "$state" .jhv)"
    work="$SCRATCH/$fig"
    mkdir -p "$work/runner"
    sed "s#@SRC@#$SRC#g" "$state" > "$work/$fig.jhv"
    echo "==> $fig"
    # launch-shot.sh puts its scratch home under RUNNER_TEMP; give it this run's folder.
    ( cd "$work" && RUNNER_TEMP="$work/runner" "$SRC/extra/launch-shot.sh" "$fig" "$SRC/run.sh" -state "$work/$fig.jhv" )
    if [ -s "$work/shot-$fig.png" ]; then
        cp "$work/shot-$fig.png" "$OUT/$fig.png"
        [ ! -f "$work/app-$fig.log" ] || cp "$work/app-$fig.log" "$OUT/$fig.log"
        echo "   $OUT/$fig.png"
    else
        echo "!! $fig: no screenshot; see $work/app-$fig.out" >&2
        failed=$((failed + 1))
    fi
done
echo "scratch kept for inspection: $SCRATCH"
[ "$failed" -eq 0 ]
