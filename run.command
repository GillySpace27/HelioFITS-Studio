#!/bin/sh
# JHelioviewer - PUNCH & coronagraph preview build.  Needs Java 25 or newer.
# Double-click on macOS, or run ./run.command (or ./run.sh) from a terminal.
cd "$(dirname "$0")" || exit 1

find_java() {
    # 1) an explicit JAVA_HOME wins
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        echo "$JAVA_HOME/bin/java"; return
    fi
    # 2) macOS: ask the OS for a 25+ JVM, then check Homebrew's keg-only locations
    if [ "$(uname)" = "Darwin" ]; then
        jh=$(/usr/libexec/java_home -v 25 2>/dev/null)
        if [ -n "$jh" ] && [ -x "$jh/bin/java" ]; then echo "$jh/bin/java"; return; fi
        for d in /opt/homebrew/opt/openjdk@25 /usr/local/opt/openjdk@25; do
            if [ -x "$d/libexec/openjdk.jdk/Contents/Home/bin/java" ]; then
                echo "$d/libexec/openjdk.jdk/Contents/Home/bin/java"; return
            fi
        done
    fi
    # 3) plain java on PATH
    if command -v java >/dev/null 2>&1; then echo java; return; fi
}

JAVA=$(find_java)
if [ -z "$JAVA" ] || ! "$JAVA" -version >/dev/null 2>&1; then
    echo "" >&2
    echo "JHelioviewer needs Java 25 or newer, which was not found." >&2
    echo "Install it, then run this again:" >&2
    echo "  - any system:      https://adoptium.net   (download Temurin 25)" >&2
    echo "  - macOS + Homebrew: brew install openjdk@25" >&2
    echo "" >&2
    exit 1
fi

exec "$JAVA" --enable-native-access=ALL-UNNAMED -jar JHelioviewer.jar "$@"
