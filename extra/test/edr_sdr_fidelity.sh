#!/bin/sh
# At gain 1 the EDR canvas must look exactly like the 10-bit canvas: same pixels within the two
# counts the 10-bit path was measured at. An untagged float layer measured 40/255 off; that is
# the failure this catches, and a flipped pass shows up as a huge difference.
#
# Runs two throwaway instances from a private home (so the settings file of any running
# JHelioviewer is never touched), each restoring the same saved state (which pins the camera,
# so the two frames are the same view), and compares screenshots:
#   sh extra/test/edr_sdr_fidelity.sh <state.jhv> [seconds-to-wait]
# Needs the jar built (ant jar). The real FileCache is linked in so the state's layers come
# from disk rather than the network.
set -e
STATE="$1"; WAIT="${2:-35}"
[ -f "$STATE" ] || { echo "usage: $0 <state.jhv> [seconds]"; exit 2; }
HOME_A=/tmp/jhv-fidelity-edr; HOME_B=/tmp/jhv-fidelity-ten
OUT=/tmp/jhv-fidelity; mkdir -p "$OUT"

seed() { # home, extra settings lines
    rm -rf "$1"; mkdir -p "$1/JHelioviewer-SWHV/Settings"
    [ -d "$HOME/JHelioviewer-SWHV/kernels" ] && ln -s "$HOME/JHelioviewer-SWHV/kernels" "$1/JHelioviewer-SWHV/kernels"
    [ -d "$HOME/JHelioviewer-SWHV/FileCache" ] && ln -s "$HOME/JHelioviewer-SWHV/FileCache" "$1/JHelioviewer-SWHV/FileCache"
    printf '%s\n' "$2" > "$1/JHelioviewer-SWHV/Settings/user.properties"
}
shoot() { # home, png
    # Pinned window size: the default derives from the screen at that instant and moved between runs.
    JHV_PREFERRED_WIDTH=1600 JHV_PREFERRED_HEIGHT=1000 java -Duser.home="$1" --enable-native-access=ALL-UNNAMED -jar HFStudio.jar -state "file://$STATE" > "$OUT/$(basename "$2" .png).log" 2>&1 &
    PID=$!
    sleep "$WAIT"
    python3 - "$PID" "$2" <<'PY'
import sys, subprocess, Quartz, AppKit
pid, out = int(sys.argv[1]), sys.argv[2]
print("screen headroom at capture:", AppKit.NSScreen.mainScreen().maximumExtendedDynamicRangeColorComponentValue())
wins = [w for w in Quartz.CGWindowListCopyWindowInfo(Quartz.kCGWindowListOptionOnScreenOnly, Quartz.kCGNullWindowID)
        if w.get('kCGWindowOwnerPID') == pid and w.get('kCGWindowLayer') == 0]
wins.sort(key=lambda w: -w['kCGWindowBounds']['Width'] * w['kCGWindowBounds']['Height'])
if not wins: sys.exit("no window for pid %d" % pid)
subprocess.run(["screencapture", "-x", "-o", "-l", str(wins[0]['kCGWindowNumber']), out], check=True)
print("captured", out, wins[0]['kCGWindowBounds'])
PY
    kill "$PID" 2>/dev/null || true
    while kill -0 "$PID" 2>/dev/null; do sleep 0.5; done   # a window still on screen makes macOS cascade the next one smaller
}

seed "$HOME_A" "display.hdrGain=1"
seed "$HOME_B" "display.edrCanvas=false"
shoot "$HOME_A" "$OUT/edr.png"
shoot "$HOME_B" "$OUT/ten.png"
grep -h -E 'EDR canvas|Deep-colour canvas|EDR headroom|fall' "$OUT"/edr.log "$OUT"/ten.log | cut -c1-160
python3 - "$OUT/edr.png" "$OUT/ten.png" <<'PY'
import sys, numpy as np
from PIL import Image
a = np.asarray(Image.open(sys.argv[1]).convert("RGB")).astype(int); b = np.asarray(Image.open(sys.argv[2]).convert("RGB")).astype(int)
h = min(a.shape[0], b.shape[0]); w = min(a.shape[1], b.shape[1]); d = np.abs(a[:h, :w] - b[:h, :w])
print(f"whole window: mean |diff| = {d.mean():.3f} counts, p99 = {np.percentile(d, 99):.1f}, max = {d.max()}, shapes {a.shape} {b.shape}")
# The verdict is taken on the miniview: it is drawn through the same canvas, pass and compositor
# as the main view, and unlike the main view it does not depend on the camera, which the state
# restore and the fit-on-load have been seen to race. Found as the lit box in the top-left
# quarter of the canvas of the 10-bit frame.
x0 = int(0.34 * w); ytop = int(0.09 * h)   # below the toolbar and the time slider
sub = b[ytop:h // 2, x0:x0 + w // 4].max(axis=2) > 25
ys, xs = np.nonzero(sub)
if len(ys) == 0: sys.exit("no miniview found in the 10-bit frame")
y0, y1, xa, xb = ys.min() + ytop, ys.max() + ytop, xs.min() + x0, xs.max() + x0
m = np.abs(a[y0:y1 + 1, xa:xb + 1] - b[y0:y1 + 1, xa:xb + 1])
print(f"miniview {y1 - y0 + 1}x{xb - xa + 1} px: mean |diff| = {m.mean():.3f} counts, p99 = {np.percentile(m, 99):.1f}, max = {m.max()}")
sys.exit(0 if m.mean() <= 2.0 else 1)
PY
