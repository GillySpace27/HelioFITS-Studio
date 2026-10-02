#!/bin/sh
# Check that every Mach-O in a HelioFITS Studio app bundle, and every Mach-O inside the jars it
# carries, is signed by team UB45PPC2JS with the hardened runtime. Read-only: the bundle is never
# modified; jars are unpacked into a scratch folder of this script's own.
#
#   release/verify_signatures.sh "<path>/HelioFITS Studio.app"
#
# Exit 0 when every Mach-O passes, 1 when any fails or none is found (a wrong path must not pass),
# 2 on a usage error. deploy_release.sh notarize runs it after signing and before the dmg is built,
# so Apple is never asked about a bundle that would be rejected for an unsigned library.
set -eu
TEAM="UB45PPC2JS"
[ $# -eq 1 ] && [ -d "$1" ] || { echo "usage: $0 <path/to/App.app>" >&2; exit 2; }
APP="$1"

JAR_TOOL=jar
[ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jar" ] && JAR_TOOL="$JAVA_HOME/bin/jar"
command -v "$JAR_TOOL" >/dev/null 2>&1 || { echo "!! no jar tool (set JAVA_HOME to a JDK)" >&2; exit 2; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/hfs-verify-XXXXXX")"
LIST="$WORK/macho.txt"
: > "$LIST"

# One line per Mach-O: "<file>\t<what to call it in the report>".
list_macho() {   # $1 = folder to search, $2 = label prefix to replace the folder with
    find "$1" -type f | while IFS= read -r _f; do
        file -b "$_f" 2>/dev/null | grep -q 'Mach-O' || continue
        printf '%s\t%s\n' "$_f" "$2${_f#"$1"}"
    done
}

list_macho "$APP" "${APP##*/}" >> "$LIST"
find "$APP" -type f -name '*.jar' > "$WORK/jars.txt"
_n=0
while IFS= read -r _j; do
    _n=$((_n + 1))
    _d="$WORK/jar$_n"
    mkdir -p "$_d"
    ( cd "$_d" && "$JAR_TOOL" xf "$_j" )
    list_macho "$_d" "${APP##*/}/${_j#"$APP"/}!" >> "$LIST"
done < "$WORK/jars.txt"

checked=0; failed=0
while IFS="$(printf '\t')" read -r _f _label; do
    checked=$((checked + 1))
    _info="$(codesign -dv --verbose=2 "$_f" 2>&1 || true)"
    _why=""
    printf '%s\n' "$_info" | grep -qx "TeamIdentifier=$TEAM" || _why="team is not $TEAM ($(printf '%s\n' "$_info" | grep -m1 '^TeamIdentifier=' || echo 'not signed'))"
    printf '%s\n' "$_info" | grep -q 'flags=0x[0-9a-f]*([^)]*runtime' || _why="${_why:+$_why; }no hardened runtime"
    if [ -n "$_why" ]; then
        failed=$((failed + 1))
        echo "FAIL $_label: $_why"
    fi
done < "$LIST"

rm -rf "$WORK"
echo "verify_signatures: $checked Mach-O checked, $failed failed (${APP##*/})"
[ "$checked" -gt 0 ] || { echo "!! no Mach-O found under $APP; is that an app bundle?" >&2; exit 1; }
[ "$failed" -eq 0 ]
