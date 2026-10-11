#!/bin/sh
# The machine half of RELEASING.md step 5: start the app inside a dmg and read its log.
#
#   release/smoke_dmg.sh <dmg> [seconds]   mount read-only, start the bundled app with a throwaway home
#                                          (under Rosetta for *-intel.dmg), wait, quit, unmount, grade the log
#   release/smoke_dmg.sh --grade <log>     grade a log only
#
# A log passes when it shows an OpenGL context (the app drew) and none of the lines below. 0.8.6's first
# dmg threw NoClassDefFoundError: java/net/http/HttpClient a second after launch, which the dev build on
# the full JDK could not show (RELEASING.md, 2026-10-11). Loading layers and exporting a movie stay a
# person's job; this only catches an app that is broken before anyone touches it.
# Exit 0 pass, 1 fail, 2 usage.
set -eu
BAD='Uncaught exception|NoClassDefFoundError|ClassNotFoundException|UnsatisfiedLinkError| SEVERE '

grade() {
    _ok=0
    if grep -Eq "$BAD" "$1"; then
        grep -En "$BAD" "$1" | cut -c1-220 | sed 's/^/!! /' >&2; _ok=1
    fi
    grep -q 'OpenGL context:' "$1" || { echo "!! no 'OpenGL context:' line: the app never drew" >&2; _ok=1; }
    [ "$_ok" = 0 ] && echo "   ok   ${1##*/}: drew, no uncaught exception or missing class"
    return "$_ok"
}

[ "${1:-}" = --grade ] && { [ -f "${2:-}" ] || { echo "usage: $0 --grade <log>" >&2; exit 2; }; grade "$2"; exit $?; }
DMG="${1:-}"; SECS="${2:-40}"
[ -f "$DMG" ] || { echo "usage: $0 <dmg> [seconds] | --grade <log>" >&2; exit 2; }

MNT="$(mktemp -d "${TMPDIR:-/tmp}/hfs-smoke-mnt-XXXXXX")"
HOME_DIR="$(mktemp -d "${TMPDIR:-/tmp}/hfs-smoke-home-XXXXXX")"
LOG="$HOME_DIR.log"
hdiutil attach -readonly -nobrowse -mountpoint "$MNT" "$DMG" >/dev/null
trap 'hdiutil detach "$MNT" >/dev/null 2>&1 || hdiutil detach -force "$MNT" >/dev/null 2>&1 || true' EXIT
APP="$(ls -d "$MNT"/*.app | head -1)"
EXE="$APP/Contents/MacOS/$(defaults read "$APP/Contents/Info" CFBundleExecutable)"
case "$DMG" in *-intel.dmg) RUN="/usr/bin/arch -x86_64" ;; *) RUN="" ;; esac
echo "==> smoke: ${DMG##*/} for ${SECS}s, home $HOME_DIR, log $LOG"
# From the empty home: the app looks for lib/natives-macos/libjhvmetalhost.dylib relative to the working
# directory before its own copy (AngleLibraries.libraryPath), so a launch from a checkout tests that
# checkout's dylib, not the dmg's. Finder starts apps in /.
( cd "$HOME_DIR" && JAVA_TOOL_OPTIONS="-Duser.home=$HOME_DIR" exec $RUN "$EXE" ) > "$LOG" 2>&1 &
PID=$!
sleep "$SECS"
ALIVE=1; kill -0 "$PID" 2>/dev/null || ALIVE=0
kill -TERM "$PID" 2>/dev/null || true
for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$PID" 2>/dev/null || break; sleep 1; done
kill -KILL "$PID" 2>/dev/null || true
[ "$ALIVE" = 1 ] || { echo "!! the app exited within ${SECS}s" >&2; tail -20 "$LOG" >&2; exit 1; }
grade "$LOG"
