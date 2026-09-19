#!/bin/bash
# Stage what jpackage bundles for one platform: the main jar and the library jars, with the native
# libraries for every other platform left out. Used by deploy_release.sh (the Mac dmg) and
# package-app.sh (Windows, Linux).
#
#   release/stage-app.sh macos-arm64|macos-x64|windows|linux <stage directory>    (from the repository root)
#
# lib/ carries natives for four platforms, about 200 MB of which any one package uses a quarter.
# Native jars are named for their platform (jhv-natives-windows.jar, lwjgl-*-natives-linux.jar,
# ...), so everything matching *-natives-*.jar goes and this platform's are put back. That pattern
# spares sqlite-jdbc-*-without-natives.jar, which is the SQLite driver itself. The cross-platform
# zip is not built from here and keeps every platform's natives, since it has to run anywhere.
set -euo pipefail
PLATFORM="$1"; STAGE="$2"
case "$PLATFORM" in
    windows)     KEEP=(-name '*-natives-windows.jar') ;;
    linux)       KEEP=(-name '*-natives-linux.jar') ;;
    # sqlite's Mac jar holds both architectures in one file.
    macos-arm64) KEEP=(-name '*-natives-macos-arm64.jar' -o -name 'sqlite-jdbc-*-natives-macos.jar') ;;
    # The Intel Mac jars are the unsuffixed *-natives-macos.jar ones (jhv's is macos-amd64 inside).
    macos-x64)   KEEP=(-name '*-natives-macos.jar') ;;
    *) echo "usage: $0 macos-arm64|macos-x64|windows|linux <stage directory>" >&2; exit 2 ;;
esac

rm -rf "$STAGE"; mkdir -p "$STAGE"
cp HFStudio.jar "$STAGE/"
cp -R lib "$STAGE/lib"
find "$STAGE/lib" -name '*-natives-*.jar' ! \( "${KEEP[@]}" \) -delete
echo "==> staged for $PLATFORM: $(find "$STAGE/lib" -name '*-natives-*.jar' | sed 's|.*/||' | tr '\n' ' ')"
