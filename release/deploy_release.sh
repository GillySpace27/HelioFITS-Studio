#!/bin/sh
# Build and publish a PUNCHStudio release: the notarized macOS dmg, the zip, and the guide.
#
#   ./deploy_release.sh package   # rebuild guide + repackage the zip locally (no network)
#   ./deploy_release.sh guide     # re-upload ONLY the guide PDF+MD to the release (fast iterate)
#   ./deploy_release.sh publish   # repackage + tag + create the GitHub release (outward)
#   ./deploy_release.sh notarize  # build a signed + notarized + stapled macOS .app/.dmg (Gatekeeper-clean)
#
# The `notarize` mode needs an Apple Developer ID cert + a notarytool keychain
# profile (see the preconditions it prints). It builds a self-contained .app
# (embedded JRE, so no Java install for users) and a stapled .dmg that opens with
# no Gatekeeper prompt and no `xattr` dance. It touches nothing the other modes
# use; the .zip stays the cross-platform (Linux/Windows) download.
#
# This script lives in <repo>/release, and the app source it packages is the repository root.
# The binary is always repackaged from the CURRENT jar at the root, so a stale zip
# can never be shipped. The guide is uploaded as its own asset, independent of
# the zip, so it stays updatable with `guide` without touching the binary.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$HERE/.." && pwd)"
# The GitHub repository, defined once. build_guide.py and the ship-punchstudio tracker read it from
# this line, so keep it in the form REPO="owner/name".
REPO="GillySpace27/PUNCHStudio"
APP_NAME="PUNCHStudio"
BUNDLE_NAME="PUNCHStudio"   # the .app on disk, kept free of spaces; APP_NAME stays the display name
# macOS 26 (Tahoe) enforces the squircle on app-bundle icons: a bare circular icon gets shrunk onto a
# grey squircle ("squircle jail"). This is the hv orb composed onto a proper squircle tile, so the
# bundled .app looks native. Regenerate with make_squircle_icon.py.
ICNS="$HERE/PUNCHStudio_icon_squircle.icns"
# The repository's VERSION file names the release: the tag, the asset names, the bundle version.
# Each release gets its OWN tag, v<version>, cut at the commit it was built from, and its own
# release object. Assets are never clobbered in place: the previous release keeps its binaries
# so a collaborator whose workflow breaks can go back to the build that worked. Shipping again
# means bumping VERSION; there is no tag override.
VERSION="$(tr -d '[:space:]' < "$SRC/VERSION")"
# Checked before any mode runs: publish does minutes of packaging before it would otherwise
# notice, and failing after the work is a good way to be ignored. jpackage needs it numeric.
echo "$VERSION" | grep -qE '^[0-9]+(\.[0-9]+){0,2}$' || {
    echo "!! VERSION is '$VERSION'; it must be numeric, like 0.8.0 (jpackage requires it, and the tag is v<version>)." >&2
    exit 2
}
TAG="v$VERSION"
# 0.x versions are pre-releases; 1.0 and later publish as normal releases
PRERELEASE=""; [ "${VERSION%%.*}" = "0" ] && PRERELEASE="--prerelease"
TITLE="$APP_NAME $VERSION"
TOP="PUNCHStudio-$VERSION"
ZIP="$HERE/$TOP.zip"
PDF="$HERE/PUNCHStudio-Guide.pdf"
MD="$HERE/PUNCHStudio-Guide.md"
CLOUD="$HERE/fabric_suvi.json.gz"   # demo point cloud for the Point Cloud layer (Open… it there)
STAGE="$HERE/.release_stage"

MODE="${1:-package}"

build_guide() {
    echo "==> regenerating guide (PDF + MD)"
    ( cd "$HERE" && python3 build_guide.py )
}

repackage() {
    echo "==> repackaging $TOP.zip from current jar ($(date))"
    rm -rf "$STAGE"
    mkdir -p "$STAGE/$TOP"
    # binary + all platform natives
    cp "$SRC/PUNCHStudio.jar" "$STAGE/$TOP/"
    cp -R "$SRC/lib" "$STAGE/$TOP/lib"
    # launchers + docs
    cp "$SRC/run.command" "$SRC/run.sh" "$SRC/run.bat" "$STAGE/$TOP/"
    cp "$ICNS" "$STAGE/$TOP/"   # shipped so zip users have the icon; the Dock tile itself
                                # comes from Taskbar.setIconImage inside the app
    # The root README.txt moved to archive/preview/ in the 0.8 cleanup. Ship whichever README
    # the root has, and say so loudly when it has none instead of aborting under set -e.
    _readme=""
    for _r in README.txt README.md; do [ -f "$SRC/$_r" ] && { _readme="$SRC/$_r"; break; }; done
    if [ -n "$_readme" ]; then cp "$_readme" "$STAGE/$TOP/"; else echo "!! no README.txt or README.md at $SRC: the zip ships without one" >&2; fi
    cp "$PDF" "$STAGE/$TOP/"
    [ -f "$SRC/LICENSE" ] && cp "$SRC/LICENSE" "$STAGE/$TOP/" || true
    chmod +x "$STAGE/$TOP/run.command" "$STAGE/$TOP/run.sh" 2>/dev/null || true
    rm -f "$ZIP"
    ( cd "$STAGE" && zip -qr "$ZIP" "$TOP" )
    rm -rf "$STAGE"
    SHA="$(shasum -a 256 "$ZIP" | awk '{print $1}')"
    SIZE="$(du -h "$ZIP" | awk '{print $1}')"
    echo "    $ZIP  ($SIZE)"
    echo "    sha256: $SHA"
}

notes_file() {
    NOTES="$(mktemp)"
    DMGSHA="$([ -f "$DMG" ] && shasum -a 256 "$DMG" | awk '{print $1}' || echo '(built by: ./deploy_release.sh notarize)')"
    # The Intel dmg is optional: the notes point Intel Macs at it only if it was built for this release.
    if [ -f "$DMG_INTEL" ]; then
        INTEL_INSTALL="**Intel Mac:** download **${DMG_INTEL##*/}**, open it, and drag **$BUNDLE_NAME** into your
Applications folder. Signed and notarized, with its own Java, like the Apple Silicon one. It has
been built and tested under Rosetta on an Apple Silicon Mac but not yet used on an Intel Mac, so
please tell us how it does."
        INTEL_FILE="- \`${DMG_INTEL##*/}\`: macOS app, Intel (signed + notarized, embedded Java; sha256 below)"
        INTEL_SHA="sha256  $(shasum -a 256 "$DMG_INTEL" | awk '{print $1}')  ${DMG_INTEL##*/}"
    else
        INTEL_INSTALL="**Intel Mac:** download **$TOP.zip**. It needs **Java 25+** (https://adoptium.net \"Temurin 25\",
or \`brew install openjdk@25\`). Unzip, then double-click \`run.command\`."
        INTEL_FILE=""; INTEL_SHA=""
    fi
    PRE_NOTE=""; [ -n "$PRERELEASE" ] && PRE_NOTE="This is a pre-release, published for testing ahead of 1.0. It is used daily on Apple Silicon Macs; Windows and Linux are new. Please report anything that breaks."
    cat > "$NOTES" <<EOF
**$APP_NAME $VERSION**

$PRE_NOTE

$APP_NAME is a fork of JHelioviewer, the open-source solar image browser from the ESA/NASA
Helioviewer Project. It streams decades of full-disk and coronagraph imagery from the major
solar observatories and plays it back as movies. It is maintained separately from JHelioviewer,
so please report problems and requests on this repository's issue tracker:
https://github.com/$REPO/issues

### Why a fork

We work with NASA's PUNCH mission and the wider coronagraph record, and JHelioviewer did not do
several things that work needed: load PUNCH data, stretch the outer corona so it has room to read,
equalize the steep radial falloff, and a few more. $APP_NAME adds them. Several of those pieces
have since been taken into JHelioviewer's own development line (the PUNCH layer, RHEF, the
Helioradial projections and the grid colour controls), and others were submitted there as pull
requests. Earlier builds were published here as the JHelioviewer PUNCH & Coronal Research
Distribution (v5.6a to v5.6d); $APP_NAME continues that line under its own name. The README on
this repository explains the fork, its relationship to JHelioviewer and its licensing in full.

### What $APP_NAME adds

**Projections**
- **Helioradial**: a Sun-centered radial re-stretch that gives the outer corona room, on one knob from linear through logarithmic to inverse. The disk itself is left unwarped.
- **Helioradial Unrolled**: the same radial stretch unrolled into a position-angle strip (earlier-stage draft).
- The redundant Polar and LogPolar projections are removed, subsumed by these.
- **Observer Sky** (experimental): a projection that looks out from the observer's own position instead of at the Sun, with the coronagraph reference surfaces along for the ride.

**Data sources**
- **PUNCH**: load NASA PUNCH mosaics from the public archive and play them as movies. (also merged into JHelioviewer, Helioviewer-Project/JHelioviewer-SWHV#328)
- **PROBA-3 / ASPIICS**: ESA's formation-flying coronagraph; loads an orbit's frames, with a cadence control and a confirmation before a multi-gigabyte download.
- A PUNCH pipeline-version selector (keeps a movie to one calibration), a per-layer archive-refresh button, and a shared display range that stops PUNCH movies strobing.
- **Coronagraph and EUV data at full depth.** LASCO C2 and C3 straight from NRL's level-0.5 archive (the VSO's LASCO catalogue stops in early 2025; NRL's is current), GOES SUVI as native L1b per channel, and AIA per channel. The layer readout states the depth measured from the pixels beside the depth the file claims, so an 8-bit browse product is named as one however wide the buffer holding it.
- **Native FITS from the VSO**: a FITS (VSO) card on the add-layer button pulls calibrated full-bit-depth FITS for most missions (LASCO, EIT, AIA, HMI, SECCHI, XRT, EIS), plus **GOES SUVI** channel by channel as native L1b, for when the 8-bit JP2 browse products band under a hard stretch.

**Image processing**
- **RHEF**: the Radial Histogram Equalization Filter, with an Upsilon control for shadows and highlights. (also taken into JHelioviewer's development line)
- **C3 with its background removed.** NRL's monthly minimum images are fetched automatically, the two bracketing each frame interpolated as their own getbkgimg.pro does, and subtracted in DN before normalization, which also puts a movie's frames on one photometric footing.

**Display**
- **HDR canvas (macOS)**: image layers render into the display's extended range, so the corona can be brighter than the window. Needs an EDR display.

**Overlays**
- Adjustable coordinate grid: color, opacity, line width, label size, and radial-label angle.

**CME tracking**
- Pick a CACTus eruption and hold its leading front at a fixed screen radius while it propagates, so you watch it evolve rather than recede.

**Point clouds**
- **Point Cloud layer**: render a scattered 3D point cloud over the Sun, colored by a per-point value, with an alpha-shape surface slider that recovers a folded surface from the points (convex hull at 100 %, the folds resolving as you drag down). Load the included \`fabric_suvi.json.gz\` demo; a rippled sheet placed in the GOES-R SUVI field of view; via the layer's **Open…** button.

**Interface**
- Reorganized layer panel: a full-width docked transport bar, a collapsible sidebar, and nested layer options.
- Discoverable timeline trim and move gestures.
- **Imagery appears while the rest is still loading** instead of after the last frame, and frames with no picture in them (SUVI darks, LASCO's daily filter sequence) are recognized and replaced by the nearest good frame.
- **Truthful export framing**: output size is an aspect plus a long side, and locking the aspect letterboxes the canvas to exactly what the export will contain.
- **Quality of life**: a small clock dial on the timestamp overlay; the colour legend as a true gradient; layers added from the File menu follow the master range including cadence; a second running instance degrades to a memory-only cache instead of flooding the log; saving a session by hand pulls its data down; assorted smaller fixes.

**Reliability**
- Gzipped FITS from NOAA load; VSO queries ask for the one channel wanted, a day at a time, so a SUVI query takes seconds rather than appearing to hang; an unreachable IPv6 address no longer fails PUNCH loads at random; files dropped on the window load themselves.

**Packaging**
- Signed and notarized macOS \`.app\` with an embedded Java runtime, so it opens with no security warning and needs no separate Java install.

### Install

**Apple Silicon Mac (recommended):** download **$TOP.dmg**, open it, and drag **$BUNDLE_NAME**
into your Applications folder. It is signed and notarized, so it opens with no security warning,
and it carries its own Java runtime, so there is nothing else to install. Just double-click.

$INTEL_INSTALL

**Windows and Linux (early):** download **$TOP-windows.zip** or **$TOP-linux.tar.gz**. Each carries
its own Java, so there is nothing else to install: unzip and run \`PUNCHStudio\\PUNCHStudio.exe\`, or untar
and run \`PUNCHStudio/bin/PUNCHStudio\`. 64-bit Intel and AMD machines only. The Windows build is not
code-signed yet, so Windows may say it "protected your PC"; choose More info, then Run anyway.
Our build service adds these two to this page about ten minutes after it is published, and only
once each has been started, drawn an image and decoded a JPEG 2000 file on a Windows and a Linux
machine without a graphics card. Nobody has used them on real hardware yet, so please tell us
how they do.

The full walkthrough is the **${PDF##*/}** asset on this release (also as \`.md\`).

### Files
- \`$TOP.dmg\`: macOS app, Apple Silicon (signed + notarized, embedded Java; sha256 below)
$INTEL_FILE
- \`$TOP.zip\`: any system with your own Java 25 installed (sha256 below)
- \`$TOP-windows.zip\` / \`$TOP-linux.tar.gz\`: Windows and Linux, embedded Java, added by the build service (sha256 appended below when they land)
- \`${PDF##*/}\` / \`.md\`: the field guide (updated independently of the binary)
- \`fabric_suvi.json.gz\`: demo point cloud; Open… it in the Point Cloud layer

\`\`\`
sha256  $DMGSHA  $TOP.dmg
$INTEL_SHA
sha256  $SHA  $TOP.zip
\`\`\`

### Licensing

Licensed under MPL 2.0, the same as JHelioviewer; the source of this release is this repository at
tag $TAG. JPEG 2000 decoding is OpenJPEG under the BSD 2-clause licence, in place of the
proprietary Kakadu codec JHelioviewer uses, which is what makes these binaries ours to give away.
Other bundled components keep their own licences and the About dialog credits them all.
EOF
    echo "$NOTES"
}

upload_guide_only() {
    echo "==> uploading guide assets only (--clobber)"
    gh release upload "$TAG" "$PDF" "$MD" --clobber --repo "$REPO"
}

publish() {
    # Refuse to touch an existing release. Overwriting one destroys the binaries someone may be
    # relying on, which is the whole thing per-release tags exist to prevent.
    if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
        echo "!! release $TAG already exists. Releases are immutable here: bump VERSION for a new one." >&2
        echo "   (To correct notes or a bad asset on the newest release, delete it deliberately by hand first.)" >&2
        exit 2
    fi

    # Tag the commit this build actually came from. Without this the release tag says nothing
    # about the source: the preview's old single tag pointed at an upstream commit that was not
    # even an ancestor of the branch it shipped from.
    BUILD_SHA="$(cd "$SRC" && git rev-parse HEAD)"
    BUILD_BRANCH="$(cd "$SRC" && git rev-parse --abbrev-ref HEAD)"
    echo "==> tagging $TAG at $BUILD_SHA ($BUILD_BRANCH)"
    [ "$BUILD_BRANCH" = master ] || echo "   (warning: releases ship from master; this build is from '$BUILD_BRANCH')"
    ( cd "$SRC" && git tag -a "$TAG" "$BUILD_SHA" -m "$TITLE" && git push origin "$TAG" )

    SHA="$(shasum -a 256 "$ZIP" | awk '{print $1}')"
    NOTES="$(notes_file)"
    # Include the notarized macOS .dmg when it's been built (via `notarize`); the notes
    # reference it, so ship it alongside the zip.
    DMG_ASSET=""; [ -f "$DMG" ] && DMG_ASSET="$DMG"
    [ -f "$DMG_INTEL" ] && [ "$DMG_INTEL" != "$DMG" ] && DMG_ASSET="$DMG_ASSET $DMG_INTEL"
    CLOUD_ASSET=""; [ -f "$CLOUD" ] && CLOUD_ASSET="$CLOUD"
    echo "==> creating release $TAG"
    gh release create "$TAG" "$ZIP" "$PDF" "$MD" $DMG_ASSET $CLOUD_ASSET \
        --repo "$REPO" --title "$TITLE" --notes-file "$NOTES" $PRERELEASE
    rm -f "$NOTES"
    echo "==> done: https://github.com/$REPO/releases/tag/$TAG"
    echo "    gilly.space/punchstudio is a download page: it asks GitHub for the newest release itself."
}

# ---- macOS signing + notarization ------------------------------------------
# Produces a Gatekeeper-clean PUNCHStudio.app (embedded JRE) inside a stapled
# .dmg. Config via env (or it auto-detects the first Developer ID it finds):
#   DEV_ID_APP     "Developer ID Application: NAME (TEAMID)"  (from: security find-identity -v -p codesigning)
#   NOTARY_PROFILE keychain profile name for notarytool        (default: jhv-notary, a legacy name; see RELEASING.md)
#   MAC_ARCH       arm64 (default) or x64. x64 builds the Intel Mac dmg, PUNCHStudio-<v>-intel.dmg,
#                  from an Intel JDK (release/.jdk-x64, Temurin 25 for mac/x64, run under Rosetta):
#                  jlink and jpackage have to be the target architecture's. The Metal host
#                  dylib is already built for both architectures.
BUNDLE_ID="space.gilly.punchstudio"
# jpackage refuses any app-version whose first number is zero, and this project ships 0.x on
# purpose, so it is handed a version it accepts and the real one is written into the bundle
# afterwards, before signing. macOS itself is content with 0.8.0; only jpackage objects.
APP_VERSION="$VERSION"         # jpackage requires a numeric version; checked at the top
case "$VERSION" in
    0.*) APP_VERSION="1.0.0" ;;
esac
MAC_ARCH="${MAC_ARCH:-arm64}"
case "$MAC_ARCH" in
    arm64) DMG="$HERE/$TOP.dmg";       ARCH_RES="jhv/macos-arm64"; STAGE_PLATFORM=macos-arm64; RECEIPT="$HERE/.notarize-run.json" ;;
    x64)   DMG="$HERE/$TOP-intel.dmg"; ARCH_RES="jhv/macos-amd64"; STAGE_PLATFORM=macos-x64;   RECEIPT="$HERE/.notarize-run-intel.json"
           [ -n "${JAVA_HOME:-}" ] && [ "$(file -b "$JAVA_HOME/bin/java" 2>/dev/null | grep -c x86_64)" = 1 ] \
               || JAVA_HOME="$HERE/.jdk-x64/Contents/Home"
           [ -x "$JAVA_HOME/bin/jpackage" ] || { echo "!! MAC_ARCH=x64 needs an Intel JDK 25 at release/.jdk-x64: unpack Temurin 25's mac/x64 JDK there (api.adoptium.net, checksum-verify it; RELEASING.md has the commands)" >&2; exit 2; } ;;
    *) echo "!! MAC_ARCH must be arm64 or x64, not '$MAC_ARCH'" >&2; exit 2 ;;
esac
DMG_INTEL="$HERE/$TOP-intel.dmg"   # publish attaches it when it exists, whichever MAC_ARCH is set
# ARCH_RES is the resource path AngleLibraries extracts the dylib from.
DYLIB="lib/natives-macos/libjhvmetalhost.dylib"

notarize_preconditions() {
    if [ -z "${JAVA_HOME:-}" ]; then
        JAVA_HOME="$(/usr/libexec/java_home -v 25 2>/dev/null || true)"
        for d in /opt/homebrew/opt/openjdk@25 /usr/local/opt/openjdk@25; do
            [ -n "$JAVA_HOME" ] && break
            [ -x "$d/libexec/openjdk.jdk/Contents/Home/bin/jpackage" ] && JAVA_HOME="$d/libexec/openjdk.jdk/Contents/Home"
        done
    fi
    [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jpackage" ] || { echo "!! set JAVA_HOME to a JDK 25 (needs jpackage)"; exit 2; }
    # The bundled runtime is jlinked from this JDK by make-runtime.sh: the modules the app
    # uses, not the whole JDK (54 MB against 295 MB on Temurin 25). Java 25's jlink can link
    # from an installed JDK without its jmods. Prefer a real .jdk bundle (Temurin) over the
    # Homebrew keg, whose symlinked layout jpackage has tripped on before.
    command -v xcrun >/dev/null 2>&1 || { echo "!! Xcode command-line tools required (xcrun not found)"; exit 2; }
    "$JAVA_HOME/bin/jpackage" --version >/dev/null 2>&1 || { echo "!! jpackage not found under JAVA_HOME=$JAVA_HOME"; exit 2; }

    : "${DEV_ID_APP:=$(security find-identity -v -p codesigning 2>/dev/null | grep 'Developer ID Application' | head -1 | sed -E 's/.*"([^"]*)".*/\1/')}"
    if [ -z "${DEV_ID_APP:-}" ]; then
        cat >&2 <<'MSG'
!! No "Developer ID Application" certificate found in the keychain.
   Once your Apple Developer membership is active:
     1. Xcode ▸ Settings ▸ Accounts ▸ (your Apple ID) ▸ Manage Certificates ▸ + ▸ Developer ID Application
        (or create it at https://developer.apple.com/account/resources/certificates and double-click to install)
     2. Verify:  security find-identity -v -p codesigning     # should list "Developer ID Application: … (TEAMID)"
     3. Re-run this, or set DEV_ID_APP="Developer ID Application: … (TEAMID)"
MSG
        exit 2
    fi

    : "${NOTARY_PROFILE:=jhv-notary}"
    # This probe hits Apple over the network, so a transient timeout must NOT be read as
    # "profile missing". Retry, and only hard-fail on a genuine credential/profile error.
    _np_ok=0
    for _t in 1 2 3; do
        if xcrun notarytool history --keychain-profile "$NOTARY_PROFILE" >/dev/null 2>"$HERE/.np.err"; then _np_ok=1; break; fi
        grep -qiE 'timed out|network|connection|could not connect' "$HERE/.np.err" && { echo "   (notary probe network blip, retry $_t)"; sleep 8; continue; }
        break
    done
    if [ "$_np_ok" != 1 ]; then
        if grep -qiE 'timed out|network|connection|could not connect' "$HERE/.np.err" 2>/dev/null; then
            echo "   (warning: couldn't reach Apple to pre-verify '$NOTARY_PROFILE'; network; continuing, the submit step is the real gate)"
        else
            cat >&2 <<MSG
!! notarytool keychain profile "$NOTARY_PROFILE" not set up. Create it once with an
   app-specific password (https://account.apple.com ▸ Sign-In and Security ▸ App-Specific Passwords):
     xcrun notarytool store-credentials "$NOTARY_PROFILE" \\
         --apple-id "gilly@nwra.com" --team-id "<TEAMID>" --password "<app-specific-password>"
   Then re-run. (Or set NOTARY_PROFILE to an existing profile.)
MSG
            exit 2
        fi
    fi
    rm -f "$HERE/.np.err"
    echo "==> signing identity : $DEV_ID_APP"
    echo "==> notary profile   : $NOTARY_PROFILE"
}

# codesign a single file with Developer ID + hardened runtime, retrying on the
# transient "timestamp server" failures Apple's TSA throws under load.
# Set CS_ENT="--entitlements <file>" before calling to attach entitlements (main exe only).
retry_codesign() {
    _f=$1; _a=0
    # Apple's TSA throttles bursts of --timestamp requests ("A timestamp was expected
    # but was not found"); a short retry window can't outlast it. ponytail: 12×30s ≈ 6 min
    # per stuck file; once the throttle clears the rest sign instantly. Widen if Apple's
    # TSA has a longer bad spell.
    while [ "$_a" -lt 12 ]; do
        _a=$((_a + 1))
        if codesign --force --timestamp --options runtime $CS_ENT --sign "$DEV_ID_APP" "$_f" 2>"$HERE/.cs.err"; then
            return 0
        fi
        if grep -qi 'timestamp' "$HERE/.cs.err"; then
            echo "   (timestamp retry $_a: $(basename "$_f"))"; sleep 30; continue
        fi
        echo "!! codesign failed on $_f:" >&2; cat "$HERE/.cs.err" >&2; return 1
    done
    echo "!! codesign timestamp kept failing on $_f" >&2; return 1
}

# Notarization unpacks jars and rejects any unsigned Mach-O inside them (lwjgl, flatlaf,
# sqlite, our ANGLE/jhv-natives, and the injected dylib). Sign each in place.
sign_jar_natives() {
    find "$1" -name '*.jar' | while IFS= read -r _j; do
        # dylib/jnilib, plus extensionless files (e.g. the bundled ffmpeg executable)
        # the file check below discards any that aren't actually Mach-O.
        _entries=$(unzip -Z1 "$_j" 2>/dev/null | grep -iE '\.(dylib|jnilib)$|(^|/)[^./]+$' || true)
        [ -z "$_entries" ] && continue
        _d=$(mktemp -d)
        printf '%s\n' "$_entries" | while IFS= read -r _e; do
            [ -z "$_e" ] && continue
            unzip -qo "$_j" "$_e" -d "$_d" 2>/dev/null || continue
            file "$_d/$_e" 2>/dev/null | grep -q 'Mach-O' || continue   # skip .so/.dll for other OSes
            CS_ENT=""; retry_codesign "$_d/$_e" || exit 1
            ( cd "$_d" && zip -q "$_j" "$_e" )                          # replace entry with the signed copy
        done || exit 1
        rm -rf "$_d"
    done
}

notarize_mac() {
    notarize_preconditions

    echo "==> building a fresh jar + dylib"
    ( cd "$SRC" && ant clean jar build-metal-host >/dev/null )
    [ -f "$SRC/$DYLIB" ] || { echo "!! $DYLIB missing after build"; exit 1; }

    APPSTAGE="$HERE/.app_stage"; OUT="$HERE/.app_out"; ENT="$HERE/.entitlements.plist"
    rm -rf "$OUT"

    # Everything jpackage bundles is the classpath: the main jar and the dependency jars (the
    # manifest Class-Path points at lib/), with only this platform's natives (stage-app.sh).
    ( cd "$SRC" && "$HERE/stage-app.sh" "$STAGE_PLATFORM" "$APPSTAGE" )

    # Inject the native dylib into the main jar at the resource path AngleLibraries reads,
    # because a .app runs with cwd=/ so the cwd-relative lib/natives-macos lookup can't
    # fire; the classpath-resource fallback is the only cwd-independent path.
    tmp="$(mktemp -d)"; mkdir -p "$tmp/$ARCH_RES"; cp "$SRC/$DYLIB" "$tmp/$ARCH_RES/"
    ( cd "$tmp" && "$JAVA_HOME/bin/jar" uf "$APPSTAGE/PUNCHStudio.jar" "$ARCH_RES/$(basename "$DYLIB")" )
    rm -rf "$tmp"

    # Hardened-runtime entitlements: the JVM JITs, and natives get extracted from jars, so
    # library validation is relaxed. Applied to the main executable only.
    cat > "$ENT" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>com.apple.security.cs.allow-jit</key><true/>
  <key>com.apple.security.cs.allow-unsigned-executable-memory</key><true/>
  <key>com.apple.security.cs.disable-library-validation</key><true/>
</dict></plist>
PLIST

    echo "==> signing native libraries inside the bundled jars"
    sign_jar_natives "$APPSTAGE"

    echo "==> jlinking the trimmed runtime from $JAVA_HOME"
    # Passed explicitly: JAVA_HOME may have been chosen in this script (MAC_ARCH=x64, or found by
    # notarize_preconditions) rather than exported by the caller.
    JAVA_HOME="$JAVA_HOME" "$HERE/make-runtime.sh" "$HERE/.runtime"
    echo "==> jpackage app-image (embeds the trimmed runtime)"
    "$JAVA_HOME/bin/jpackage" \
        --type app-image --name "$BUNDLE_NAME" --app-version "$APP_VERSION" \
        --input "$APPSTAGE" --main-jar PUNCHStudio.jar \
        --main-class org.helioviewer.jhv.PUNCHStudio \
        --java-options "--enable-native-access=ALL-UNNAMED" \
        --java-options "--add-exports=java.desktop/sun.awt=ALL-UNNAMED" \
        --java-options "--add-exports=java.desktop/sun.swing=ALL-UNNAMED" \
        --mac-package-identifier "$BUNDLE_ID" \
        --icon "$ICNS" \
        --runtime-image "$HERE/.runtime" \
        --dest "$OUT"
    APP="$OUT/$BUNDLE_NAME.app"
    [ -d "$APP" ] || { echo "!! jpackage produced no .app"; exit 1; }

    if [ "$APP_VERSION" != "$VERSION" ]; then
        echo "==> writing the real version $VERSION into the bundle (jpackage was given $APP_VERSION)"
        /usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $VERSION" "$APP/Contents/Info.plist"
        /usr/libexec/PlistBuddy -c "Set :CFBundleVersion $VERSION" "$APP/Contents/Info.plist"
    fi

    # Prove the bundled app actually starts (catches missing deps / broken native load)
    # before spending a multi-minute notary round-trip on it.
    echo "==> smoke-testing the bundled app"
    "$APP/Contents/MacOS/$BUNDLE_NAME" >"$HERE/.app_smoke.log" 2>&1 &
    _smoke=$!; _ok=0
    for _i in 1 2 3 4 5 6 7 8 9 10 11 12; do
        grep -qi 'Start main window' "$HERE/.app_smoke.log" 2>/dev/null && { _ok=1; break; }
        grep -qiE 'Exception|Error:|NoClassDef' "$HERE/.app_smoke.log" 2>/dev/null && break
        sleep 2
    done
    kill "$_smoke" 2>/dev/null || true
    if [ "$_ok" != 1 ]; then
        echo "!! bundled app did not reach 'Start main window':"; tail -15 "$HERE/.app_smoke.log"; exit 1
    fi
    echo "   app launches OK"; rm -f "$HERE/.app_smoke.log"

    echo "==> codesign (nested Mach-O first, then the bundle)"
    find "$APP/Contents" -type f | while IFS= read -r f; do
        if file "$f" 2>/dev/null | grep -q 'Mach-O'; then
            CS_ENT=""; retry_codesign "$f" || exit 1
        fi
    done
    CS_ENT="--entitlements $ENT"; retry_codesign "$APP" || exit 1
    codesign --verify --deep --strict --verbose=2 "$APP"

    echo "==> building + signing the .dmg"
    rm -f "$DMG"
    # Build the image from a staging folder, not from the .app directly: without a symlink to
    # /Applications next to it there is nothing in the mounted volume to drag onto, and users
    # who do not know the convention end up running the app from the read-only image.
    DMGSTAGE="$HERE/.dmg_stage"
    rm -rf "$DMGSTAGE"; mkdir -p "$DMGSTAGE"
    cp -R "$APP" "$DMGSTAGE/"
    ln -s /Applications "$DMGSTAGE/Applications"
    hdiutil create -quiet -volname "$APP_NAME" -srcfolder "$DMGSTAGE" -ov -format UDZO "$DMG"
    rm -rf "$DMGSTAGE"
    # Signing the dmg is a courtesy; Gatekeeper checks the stapled ticket, not the dmg
    # signature. Apple's timestamp server occasionally blips, so retry, then continue
    # unsigned rather than abort (the .app inside is signed + will be notarized + stapled).
    dmg_signed=0
    for attempt in 1 2 3; do
        if codesign --force --timestamp --sign "$DEV_ID_APP" "$DMG" 2>/dev/null; then dmg_signed=1; break; fi
        echo "   dmg codesign attempt $attempt failed (timestamp server?); retrying in 5s..."
        sleep 5
    done
    [ "$dmg_signed" = 1 ] || echo "   continuing without a dmg signature (not required for notarization)"

    echo "==> notarizing (this waits for Apple; usually a few minutes)"
    # macOS has no `timeout(1)`; perl's alarm is always present. Guard the wait so a hung
    # connection to Apple fails the attempt instead of blocking forever.
    perl -e 'alarm shift; exec @ARGV' 600 \
        xcrun notarytool submit "$DMG" --keychain-profile "$NOTARY_PROFILE" --wait
    echo "==> stapling the ticket"
    # Stapling downloads the ticket from Apple's CloudKit, which can hang for minutes even
    # after the submission is Accepted. Guard each attempt with perl's alarm (no `timeout` on
    # macOS) so a stuck CloudKit call is killed and retried; the ticket already exists server-side.
    _stapled=0
    for _s in 1 2 3 4 5; do
        if perl -e 'alarm shift; exec @ARGV' 50 xcrun stapler staple "$DMG" 2>&1 | tail -1 | grep -qi 'worked'; then _stapled=1; break; fi
        echo "   staple attempt $_s failed (network/hang?); retrying in 10s..."; sleep 10
    done
    [ "$_stapled" = 1 ] || { echo "!! stapling kept failing. The dmg IS notarized; re-run just:  xcrun stapler staple \"$DMG\""; exit 1; }

    echo "==> verifying"
    xcrun stapler validate "$DMG"
    spctl -a -t open --context context:primary-signature -vv "$DMG" || true

    # Receipt: this dmg, by content hash, came out of a run that stapled AND validated.
    # The tracker needs a way to assert "this exact file is the notarized one" without
    # re-running `stapler validate`, which talks to Apple's CloudKit and is wildly
    # non-deterministic: measured 0.3s cached, 30s warm, and 60s-then-exit-68 cold on
    # 2026-08-23. A check that intermittently calls a good dmg unnotarized is one you
    # learn to ignore. Everything above this line ran under `set -e`, so reaching here
    # means the staple and the validate both succeeded.
    cat > "$RECEIPT" <<EOF
{
  "dmg_sha256": "$(shasum -a 256 "$DMG" | awk '{print $1}')",
  "build_sha": "$(cd "$SRC" && git rev-parse HEAD)",
  "build_revision": "$(cd "$SRC" && git rev-list --count HEAD)",
  "notarized_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF
    echo "==> receipt written: ${RECEIPT##*/}"

    rm -rf "$APPSTAGE" "$OUT" "$ENT"
    echo "==> done: $DMG  ($(du -h "$DMG" | awk '{print $1}'))"
    echo "    publish attaches it to the release automatically when it exists."
}

case "$MODE" in
    package)  build_guide; repackage ;;
    guide)    build_guide; upload_guide_only ;;
    publish)  build_guide; repackage; publish ;;
    notarize) notarize_mac ;;
    *) echo "usage: $0 {package|guide|publish|notarize}"; exit 2 ;;
esac
