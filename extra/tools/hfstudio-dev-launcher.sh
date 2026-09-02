#!/bin/sh
# HFStudio dev launcher: rebuild from source, then run whatever that produced.
#
# The copy that runs lives in ~/Desktop/HFStudio (dev).app/Contents/MacOS/launch; this is the source
# of it, kept here so it is not the only copy. After editing, copy it back into the bundle and
# re-sign: codesign --force --deep -s - "$HOME/Desktop/HFStudio (dev).app"
#
# The JHelioviewer (dev) launcher this replaces only ran the jar it found, so opening it after a
# source change quietly gave the previous build. This one runs `ant jar` first, which is 2 to 3
# seconds when nothing changed, and offline: the kernel step leaves its product alone once built.
#
# ponytail: invokes ant and java directly rather than delegating to the repo's run.command --
# Gatekeeper refuses to let this signed bundle exec that unsigned .command script. A Finder
# launch also gets a bare PATH (/usr/bin:/bin:/usr/sbin:/sbin), so every tool is found by
# absolute path or by asking the OS.
BUILD="$HOME/Documents/NWRA/PUNCH_Science/jhv-demo"
JAR="HFStudio.jar"
LOG="/tmp/hfstudio-dev-build.log"
LOCK="/tmp/hfstudio-dev-build.lock"

alert() { # title, message
    osascript -e "display alert \"$1\" message \"$2\"" >/dev/null 2>&1
}

find_java_home() {
    [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ] && { echo "$JAVA_HOME"; return; }
    jh=$(/usr/libexec/java_home -v 25 2>/dev/null)
    [ -n "$jh" ] && [ -x "$jh/bin/java" ] && { echo "$jh"; return; }
    for d in /opt/homebrew/opt/openjdk@25 /usr/local/opt/openjdk@25; do
        [ -x "$d/libexec/openjdk.jdk/Contents/Home/bin/java" ] && \
            { echo "$d/libexec/openjdk.jdk/Contents/Home"; return; }
    done
}

find_ant() {
    for a in /opt/homebrew/bin/ant /usr/local/bin/ant /usr/bin/ant; do
        [ -x "$a" ] && { echo "$a"; return; }
    done
    command -v ant 2>/dev/null
}

[ -d "$BUILD" ] || { alert "HFStudio source not found" "Expected $BUILD"; exit 1; }
cd "$BUILD" || exit 1

JH=$(find_java_home)
[ -n "$JH" ] || {
    alert "Java 25 not found" "Install Temurin 25 from adoptium.net, or: brew install openjdk@25"
    exit 1
}

# A second double-click while the first build runs would have two ants writing one tree. Wait for
# the first to finish (mkdir is atomic), then launch what it built rather than building again.
built=no
if mkdir "$LOCK" 2>/dev/null; then
    trap 'rmdir "$LOCK" 2>/dev/null' EXIT INT TERM
    ANT=$(find_ant)
    if [ -n "$ANT" ]; then
        JAVA_HOME="$JH" "$ANT" jar > "$LOG" 2>&1
        status=$?
        built=yes
    else
        status=0
        [ -f "$JAR" ] || { alert "Ant not found" "Install it with: brew install ant"; exit 1; }
        alert "Ant not found" "Launching the previous build without recompiling. Install ant with: brew install ant"
    fi
    rmdir "$LOCK" 2>/dev/null
    trap - EXIT INT TERM
else
    i=0
    while [ -d "$LOCK" ] && [ $i -lt 120 ]; do sleep 1; i=$((i + 1)); done
    status=0
fi

if [ "$built" = yes ] && [ $status -ne 0 ]; then
    tail=$(grep -m 3 -E 'error:|BUILD FAILED' "$LOG" | tr '"' "'" | tr '\n' ' ')
    if [ -f "$JAR" ]; then
        answer=$(osascript -e "display alert \"HFStudio build failed\" message \"$tail

Log: $LOG\" buttons {\"Cancel\", \"Launch previous build\"} default button \"Cancel\"" 2>/dev/null)
        case "$answer" in
            *"Launch previous build"*) ;;
            *) exit 1 ;;
        esac
    else
        alert "HFStudio build failed" "$tail

Log: $LOG"
        exit 1
    fi
fi

[ -f "$JAR" ] || { alert "HFStudio build not found" "Expected $BUILD/$JAR"; exit 1; }
exec "$JH/bin/java" --enable-native-access=ALL-UNNAMED -jar "$JAR" "$@"
