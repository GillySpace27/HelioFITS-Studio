#!/bin/sh
# Judge one launch of the application from what it left behind. Used by .github/workflows/launch.yml.
#
#   extra/launch-judge.sh <app log> <alive marker> <launcher output>
#
# Fails unless the main window came up, a graphics context was created, nothing was thrown
# uncaught, and the process was still running when the screenshot was taken. Prints the evidence
# either way, since a pass on a software renderer is worth reading, not just trusting.
LOG="$1"; ALIVE="$2"; OUT="$3"
fail=0
say() { echo "$1"; }

if [ ! -s "$LOG" ]; then
    say "FAIL no application log: it never got as far as starting logging"
    echo "--- launcher output ---"; tail -40 "$OUT" 2>/dev/null
    exit 1
fi

echo "--- what it said about itself ---"
grep -E 'loadVersion|Start main window|OpenGL context|ANGLE EGL config|Load enabled plugins' "$LOG" | cut -c1-260

grep -q 'Start main window' "$LOG" || { say "FAIL main window never started"; fail=1; }
grep -q 'OpenGL context' "$LOG"    || { say "FAIL no graphics context was created"; fail=1; }
[ -f "$ALIVE" ]                    || { say "FAIL the process had exited before the screenshot"; fail=1; }
if grep -q 'Uncaught exception' "$LOG"; then
    say "FAIL an exception went uncaught"; fail=1
fi
# OpenJpeg's own message when the bundled decoder will not load, which on Windows usually means
# the Visual C++ runtime is missing. The app keeps running without it, so only the log says so.
if grep -q 'No JPEG 2000 decoder' "$LOG"; then
    say "FAIL the JPEG 2000 decoder did not load"; fail=1
fi

# SEVERE lines and the exception each names, not every WARNING: a network retry is not news.
echo "--- SEVERE (first 20, each with the line after) ---"
grep -n -A1 'SEVERE' "$LOG" | head -40 | cut -c1-260
grep -n 'Exception in thread' "$LOG" | head -10 | cut -c1-260
if [ $fail -ne 0 ]; then
    echo "--- launcher output ---"; tail -40 "$OUT" 2>/dev/null
    exit 1
fi
say "PASS launched, created a graphics context, and was still running; look at the screenshot"
