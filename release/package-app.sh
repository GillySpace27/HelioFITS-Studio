#!/bin/bash
# Package HFStudio for Windows or Linux with its own Java, so nobody has to install anything:
# the counterpart of what `deploy_release.sh notarize` builds for the Mac.
#
#   release/package-app.sh windows|linux|macos-x64     (run from the repository root, after `ant jar`)
#
# jpackage only builds for the system it runs on, which is why this runs in CI
# (.github/workflows/package.yml) rather than on the Mac. Output, named so the download page's
# platform tiles recognise it:
#   HFStudio-<version>-windows.zip     unzip anywhere, run HFStudio\HFStudio.exe
#   HFStudio-<version>-linux.tar.gz    untar anywhere, run HFStudio/bin/HFStudio
# An app-image rather than an installer: no WiX toolchain, no admin rights, nothing to uninstall.
# macos-x64 builds an UNSIGNED pkg-out/HFStudio.app and no archive: it exists so CI can start the
# Intel Mac build on an Intel Mac. The dmg people download is built and signed by
# `MAC_ARCH=x64 deploy_release.sh notarize`, which stages it the same way.
set -euo pipefail
OS="$1"
case "$OS" in windows|linux|macos-x64) ;; *) echo "usage: $0 windows|linux|macos-x64" >&2; exit 2 ;; esac
VERSION="$(tr -d '[:space:]' < VERSION)"
[ -f HFStudio.jar ] || { echo "!! no HFStudio.jar: run 'ant jar' first" >&2; exit 1; }

rm -rf pkg-out; mkdir -p pkg-out
# Everything jpackage bundles is the classpath: the main jar and the dependency jars, with only
# this platform's natives (stage-app.sh). AppInit unpacks them at start.
release/stage-app.sh "$OS" pkg-stage

# The Mac icon, redrawn at the sizes each system asks for.
PY="$(command -v python3 || command -v python)"
if [ "$OS" = windows ]; then ICON=pkg-icon.ico; else ICON=pkg-icon.png; fi
[ "$OS" = macos-x64 ] && ICON=release/HFStudio_icon_squircle.icns
[ "$OS" = macos-x64 ] || "$PY" - "$ICON" <<'EOF'
import sys
from PIL import Image
im = Image.open("release/HFStudio_icon_squircle.icns")
im.load()
out = sys.argv[1]
if out.endswith(".ico"):
    im.save(out, sizes=[(256, 256), (64, 64), (48, 48), (32, 32), (16, 16)])
else:
    im.resize((512, 512)).save(out)
EOF

# A .app runs with its working directory at /, so the Metal host dylib cannot be found beside the
# app; it goes into the main jar at the resource path AngleLibraries reads, as deploy_release.sh
# does. Built for both architectures by `ant jar`.
if [ "$OS" = macos-x64 ]; then
    tmp="$(mktemp -d)"; mkdir -p "$tmp/jhv/macos-amd64"
    cp lib/natives-macos/libjhvmetalhost.dylib "$tmp/jhv/macos-amd64/"
    ( cd "$tmp" && jar uf "$OLDPWD/pkg-stage/HFStudio.jar" jhv/macos-amd64/libjhvmetalhost.dylib )
    rm -rf "$tmp"
fi

# The same trimmed runtime as the Mac bundle: see make-runtime.sh for what is in it and why.
release/make-runtime.sh pkg-runtime

# The same launch options as the Mac bundle. The jar's manifest says Add-Exports, but that is only
# honoured for `java -jar`; a native launcher starts the main class and needs them spelled out.
# jpackage on macOS refuses a version whose first number is zero; the bundle is never shipped.
APP_VERSION="$VERSION"; [ "$OS" = macos-x64 ] && case "$VERSION" in 0.*) APP_VERSION=1.0.0 ;; esac
jpackage --type app-image --name HFStudio --app-version "$APP_VERSION" \
    --input pkg-stage --main-jar HFStudio.jar --main-class org.helioviewer.jhv.HFStudio \
    --java-options "--enable-native-access=ALL-UNNAMED" \
    --java-options "--add-exports=java.desktop/sun.awt=ALL-UNNAMED" \
    --java-options "--add-exports=java.desktop/sun.swing=ALL-UNNAMED" \
    --icon "$ICON" \
    --runtime-image pkg-runtime \
    --dest pkg-out

if [ "$OS" = macos-x64 ]; then
    echo "==> pkg-out/HFStudio.app (unsigned, for testing) $(du -sh pkg-out/HFStudio.app | cut -f1)"
    exit 0
fi
if [ "$OS" = windows ]; then
    # The bundled JPEG 2000 decoder imports vcruntime140.dll. The JVM loads the copy in its own
    # runtime first, and Windows then reuses it, so a machine without the Visual C++
    # redistributable still decodes. That holds only while the runtime carries the file, which
    # after trimming means only while jlink keeps it with java.base.
    [ -f pkg-out/HFStudio/runtime/bin/vcruntime140.dll ] \
        || { echo "!! the bundled runtime has no vcruntime140.dll; OpenJPEG would not load on a bare Windows" >&2; exit 1; }
    ARCHIVE="HFStudio-$VERSION-windows.zip"
    ( cd pkg-out && 7z a -tzip -mx=7 "../$ARCHIVE" HFStudio > /dev/null )
else
    ARCHIVE="HFStudio-$VERSION-linux.tar.gz"
    tar -C pkg-out -czf "$ARCHIVE" HFStudio
fi
echo "==> $ARCHIVE ($(du -h "$ARCHIVE" | cut -f1))"
