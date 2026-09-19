#!/bin/bash
# Build the trimmed Java runtime every HFStudio package carries: the Mac dmg (deploy_release.sh)
# and the Windows and Linux packages (package-app.sh).
#
#   release/make-runtime.sh <output directory>     (uses the JDK at $JAVA_HOME)
#
# The packages used to embed the whole JDK, compiler and all. The application needs a fraction of
# it. The modules below are what `jdeps --print-module-deps` finds across the application and every
# library jar in lib/, which is only what code refers to directly, plus the ones loaded by name at
# run time, which jdeps cannot see:
#   jdk.zipfs          FileUtils opens zip archives as a filesystem ("jar:" URIs)
#   jdk.charsets       the less common text encodings, for data that arrives in one
#   jdk.accessibility  screen-reader support (the Java Access Bridge on Windows)
# Left out on purpose: jdk.localedata. The application formats with fixed locales, so a German or
# French machine gets English date and number formats rather than an error.
#
# If a feature fails only in a packaged build with NoClassDefFoundError or "module not found",
# a module is missing from this list: rerun jdeps (the command is in the commit that added this
# file) and look for the name the error gives.
set -euo pipefail
OUT="$1"
MODULES=java.base,java.compiler,java.desktop,java.instrument,java.naming,java.prefs,java.sql,jdk.management,jdk.unsupported,jdk.zipfs,jdk.charsets,jdk.accessibility

rm -rf "$OUT"
"$JAVA_HOME/bin/jlink" --add-modules "$MODULES" \
    --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
    --output "$OUT"
