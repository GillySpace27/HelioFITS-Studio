#!/bin/sh
# Build and publish a HelioFITS Studio release: the notarized macOS dmg, the zip, and the guide.
#
#   ./deploy_release.sh package   # rebuild guide + repackage the zip locally (no network)
#   ./deploy_release.sh guide     # re-upload ONLY the guide PDF+MD to the release (fast iterate); strict build,
#                                 # em-dash scan, then the typed tag or HFS_GUIDE_APPROVED
#   ./deploy_release.sh publish   # repackage + tag + create the GitHub release (outward; typed tag or HFS_PUBLISH_APPROVED)
#   ./deploy_release.sh publish --dry-run  # publish's checks and the gate text; tags, pushes, uploads nothing
#   ./deploy_release.sh notarize  # build a signed + notarized + stapled macOS .app/.dmg (Gatekeeper-clean)
#   ./deploy_release.sh notarize-resume  # finish the submission recorded in release/.notarize-pending*.json
#   ./deploy_release.sh notes     # print the release notes publish would post (no network)
#   ./deploy_release.sh assets [local|ci|all]  # the release's asset names, from release/assets.txt
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
# The GitHub repository, defined once. build_guide.py and the ship-hfstudio tracker read it from
# this line, so keep it in the form REPO="owner/name".
REPO="GillySpace27/HelioFITS-Studio"
APP_NAME="HelioFITS Studio"
BUNDLE_NAME="HelioFITS Studio"   # the .app on disk, so Applications and the Dock read the product name.
# It has a space in it: every use of it and of $APP below is quoted, and the loops over files
# inside the bundle read with `while IFS= read -r`, so keep it that way. Files and folders keep the
# one-word HFStudio (TOP, the jar, the icons), as does the Windows and Linux package.
# macOS 26 (Tahoe) enforces the squircle on app-bundle icons: art that does not fill it gets shrunk
# onto a grey plate ("squircle jail"). This art fills it. Regenerate both this and the asset catalog
# beside it with make_app_icon.py.
#
# The .icns alone is not enough. At 16 and 32 pixels macOS 26 plates a bundle whose icon is only an
# .icns no matter what the art does, so the catalog below is compiled in as well; the .icns stays
# for the disk image's volume icon and for older systems.
ICNS="$HERE/HFStudio_icon.icns"
APPICONSET="$HERE/AppIcon.appiconset"
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
TOP="HFStudio-$VERSION"
ZIP="$HERE/$TOP.zip"
PDF="$HERE/HFStudio-Guide.pdf"
MD="$HERE/HFStudio-Guide.md"
CLOUD="$HERE/fabric_suvi.json.gz"   # demo point cloud for the Point Cloud layer (Open… it there)
STAGE="$HERE/.release_stage"

MODE="${1:-package}"
# publish --dry-run runs every check publish makes and prints the gate text for Gilly, then stops.
# Any other second argument to publish is refused: a mistyped flag must not fall through to a real publish.
DRY_RUN=""
case "$MODE:${2:-}" in
    *:) ;;
    publish:--dry-run) DRY_RUN=1 ;;
    assets:*) ;;
    *) echo "!! unknown option '$2' for $MODE (only publish takes one: --dry-run)" >&2; exit 2 ;;
esac
# The release's asset names, kept in one file. publish, the ship-hfstudio tracker and package.yml's
# attach job read it; nothing else names the assets.
ASSETS_FILE="$HERE/assets.txt"

build_guide() {
    echo "==> regenerating guide (PDF + MD)"
    ( cd "$HERE" && python3 build_guide.py "$@" )
}

repackage() {
    echo "==> repackaging $TOP.zip from current jar ($(date))"
    rm -rf "$STAGE"
    mkdir -p "$STAGE/$TOP"
    # binary + all platform natives
    cp "$SRC/HFStudio.jar" "$STAGE/$TOP/"
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
    # What is new in this version, lifted from changelog.md so the release page and the changelog
    # cannot disagree. Its ### headings drop a level to sit under the page's own.
    WHATSNEW="$(awk -v v="$VERSION" 'index($0, "## ") == 1 { if (on) exit; on = index($0, " " v " ") > 0; next } on' "$SRC/changelog.md" | sed 's/^### /#### /')"
    [ -n "$WHATSNEW" ] || { echo "changelog.md has no section for $VERSION; write one before publishing" >&2; exit 1; }
    PRE_NOTE=""; [ -n "$PRERELEASE" ] && PRE_NOTE="This is a pre-release, published for testing ahead of 1.0. It is used daily on Apple Silicon Macs; Windows and Linux are new. Please report anything that breaks."
    # The fork's standing description, kept as prose in notes-preamble.md; @APP_NAME@ and @REPO@ in it
    # are filled in here. Command substitution drops its trailing newlines; the heredoc puts them back.
    [ -f "$HERE/notes-preamble.md" ] || { echo "!! release/notes-preamble.md is missing" >&2; exit 1; }
    PREAMBLE="$(sed -e "s|@APP_NAME@|$APP_NAME|g" -e "s|@REPO@|$REPO|g" "$HERE/notes-preamble.md")"
    cat > "$NOTES" <<EOF
**$APP_NAME $VERSION**

$PRE_NOTE

### New in $VERSION

$WHATSNEW

$PREAMBLE

### Install

**Apple Silicon Mac (recommended):** download **$TOP.dmg**, open it, and drag **$BUNDLE_NAME**
into your Applications folder. It is signed and notarized, so it opens with no security warning,
and it carries its own Java runtime, so there is nothing else to install. Just double-click.

$INTEL_INSTALL

**Windows and Linux (early):** download **$TOP-windows.zip** or **$TOP-linux.tar.gz**. Each carries
its own Java, so there is nothing else to install: unzip and run \`HFStudio\HFStudio.exe\`, or untar
and run \`HFStudio/bin/HFStudio\`. 64-bit Intel and AMD machines only. The Windows build is not
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

# Exit 2 when any file named after $1 (the verb for what was not done) holds an em dash (U+2014) or
# cannot be scanned. grep exits 0 for a match, 1 for none and 2 for an error such as a missing file;
# only 1 passes, so a file that is not there can never read as "no em dash".
scan_em_dash() {
    _what="$1"; shift
    _rc=0
    LC_ALL=C grep -n "$(printf '\342\200\224')" "$@" >&2 || _rc=$?
    case "$_rc" in
        1) ;;
        0) echo "!! em dash (U+2014) in the release notes or guide ($*; lines above); fix the source, nothing was $_what" >&2; exit 2 ;;
        *) echo "!! could not scan $* for em dashes (grep exit $_rc); nothing was $_what" >&2; exit 2 ;;
    esac
}

upload_guide_only() {
    # The single upload that replaces files on a published release, and only the guide's two, never a
    # binary. Outward like publish, so the same per-action gate: the exact tag typed at a terminal, or,
    # with no terminal, HFS_GUIDE_APPROVED set to that tag for this one run after Gilly's yes in chat.
    if [ -n "${HFS_GUIDE_APPROVED:-}" ]; then
        [ "$HFS_GUIDE_APPROVED" = "$TAG" ] \
            || { echo "!! HFS_GUIDE_APPROVED is '$HFS_GUIDE_APPROVED', not $TAG; a yes is for one release" >&2; exit 2; }
    elif [ -t 0 ]; then
        printf 'Replace HFStudio-Guide.pdf and .md on the published %s? Type the tag: ' "$TAG"
        read -r _typed
        [ "$_typed" = "$TAG" ] || { echo "!! typed '$_typed', not $TAG; nothing uploaded" >&2; exit 2; }
    else
        echo "!! no terminal: the guide upload runs only with HFS_GUIDE_APPROVED=$TAG, after Gilly's yes" >&2
        exit 2
    fi
    echo "==> uploading guide assets only (--clobber)"
    gh release upload "$TAG" "$PDF" "$MD" --clobber --repo "$REPO"
}

# The per-tag gate on the outward half of publish (the tag push and the release), the same shape as the
# one upload_guide_only has: the exact tag typed at a terminal, or, with no terminal, HFS_PUBLISH_APPROVED
# set to that tag for this one run after Gilly's yes in chat. release/ship.sh publish asks the same
# question first and hands its answer down in HFS_PUBLISH_APPROVED, so a human types the tag once.
approve_publish() {
    if [ -n "${HFS_PUBLISH_APPROVED:-}" ]; then
        [ "$HFS_PUBLISH_APPROVED" = "$TAG" ] \
            || { echo "!! HFS_PUBLISH_APPROVED is '$HFS_PUBLISH_APPROVED', not $TAG; a yes is for one tag" >&2; exit 2; }
    elif [ -t 0 ]; then
        printf 'Gilly said yes to publishing exactly this tag. Type it (%s): ' "$TAG"
        read -r _typed
        [ "$_typed" = "$TAG" ] || { echo "!! typed '$_typed', not $TAG; nothing published" >&2; exit 2; }
    else
        echo "!! no terminal: publish runs only with HFS_PUBLISH_APPROVED=$TAG set for this one invocation, after Gilly's yes in chat" >&2
        exit 2
    fi
}

# Print the asset names from assets.txt with {v} expanded, one per line; $1 is local, ci or all. A row
# that is not "<pattern> local|ci" stops the script: a misspelt list would ship the wrong files.
asset_names() {
    [ -f "$ASSETS_FILE" ] || { echo "!! $ASSETS_FILE is missing" >&2; exit 1; }
    awk -v v="$VERSION" -v want="$1" '
        /^#/ || NF == 0 { next }
        NF != 2 || ($2 != "local" && $2 != "ci") { print "!! assets.txt:" NR ": want <pattern> local|ci, got: " $0 > "/dev/stderr"; bad = 1; next }
        want == "all" || $2 == want { gsub(/[{]v[}]/, v, $1); print $1 }
        END { exit bad }' "$ASSETS_FILE"
}

# The receipt notarize_mac writes for a dmg (see the receipt writer at the end of notarize_mac).
receipt_for() {
    case "$1" in
        *-intel.dmg) echo "$HERE/.notarize-run-intel.json" ;;
        *)           echo "$HERE/.notarize-run.json" ;;
    esac
}

# One key of a receipt, or nothing when the file or key is missing or the JSON does not parse.
receipt_key() {
    python3 -c 'import json, sys; print(json.load(open(sys.argv[1])).get(sys.argv[2], ""))' "$1" "$2" 2>/dev/null || true
}

# One main-section attribute of the jar's manifest, or nothing.
manifest_attr() {
    unzip -p "$SRC/HFStudio.jar" META-INF/MANIFEST.MF 2>/dev/null | tr -d '\r' | awk -F': ' -v k="$1" '$1 == k { print $2; exit }'
}

# The commit tag $TAG names, locally ($1 = local) or on origin ($1 = origin); nothing when absent.
tag_target() {
    if [ "$1" = local ]; then
        ( cd "$SRC" && git rev-parse -q --verify "refs/tags/$TAG^{commit}" ) || true
    else
        ( cd "$SRC" && git ls-remote --tags origin "refs/tags/$TAG" "refs/tags/$TAG^{}" 2>/dev/null ) \
            | awk -v t="refs/tags/$TAG" '$2 == t "^{}" { p = $1 } $2 == t { l = $1 } END { print (p != "" ? p : l) }'
    fi
}

# Record one reason preflight_publish will refuse; $2 is the RELEASING.md step that fixes it.
refuse() {
    echo "!! $1; rerun RELEASING.md step $2" >&2
    REFUSED=1
}

# Everything publish has to be able to prove before it tags or uploads anything. Every failed check
# is reported, then it exits 2. Sets WAY_BACK, the release that stays behind this one.
preflight_publish() {
    REFUSED=0
    _head="$(cd "$SRC" && git rev-parse HEAD)"
    _branch="$(cd "$SRC" && git rev-parse --abbrev-ref HEAD)"
    [ "$_branch" = master ] || refuse "on branch '$_branch'; releases ship from master" 1
    [ -z "$(cd "$SRC" && git status --porcelain src resources VERSION)" ] \
        || refuse "uncommitted changes in src, resources or VERSION would ship without a commit" 1
    _remote="$(cd "$SRC" && git ls-remote origin refs/heads/master 2>/dev/null | awk '{ print $1 }')"
    [ "$_remote" = "$_head" ] || refuse "HEAD $_head is not origin/master (${_remote:-unreadable})" 1

    # The jar records the commit it was built from (build.xml); it has to be this one.
    if [ -f "$SRC/HFStudio.jar" ]; then
        _rev="$(manifest_attr revision)"
        _count="$(cd "$SRC" && git rev-list --count HEAD)"
        [ "$_rev" = "$_count" ] || refuse "HFStudio.jar has revision '${_rev:-none}', HEAD is $_count" 2
        # commit and dirty are in the manifest once build.xml stamps them; before that, not checked.
        _mcommit="$(manifest_attr commit)"
        if [ -n "$_mcommit" ]; then
            [ "$_mcommit" = "$(cd "$SRC" && git rev-parse --short=12 HEAD)" ] \
                || refuse "HFStudio.jar was built from commit $_mcommit, not HEAD" 2
            [ "$(manifest_attr dirty)" = false ] || refuse "HFStudio.jar was built from a dirty tree" 2
        fi
    else
        refuse "no HFStudio.jar at the repository root" 2
    fi

    # Every dmg publish would attach is the file a notarize run stapled and validated, from this commit.
    # The zip and the guide are rebuilt by publish right after this; the rest must already exist.
    _names="$(asset_names local)"
    for _n in $_names; do
        case "$_n" in
            *.dmg)
                _f="$HERE/$_n"; _r="$(receipt_for "$_n")"
                if [ ! -f "$_f" ]; then
                    case "$_n" in *-intel.dmg) continue ;; esac   # optional: the notes then send Intel Macs to the zip
                    refuse "no $_n in release/; notarize builds it" 4; continue
                fi
                if [ ! -f "$_r" ]; then
                    refuse "$_n has no receipt ${_r##*/}, so no notarize run vouches for it" 4; continue
                fi
                _want="$(receipt_key "$_r" dmg_sha256)"
                _built="$(receipt_key "$_r" build_sha)"
                _have="$(shasum -a 256 "$_f" | awk '{ print $1 }')"
                [ "$_have" = "$_want" ] || refuse "$_n (sha256 $_have) is not the notarized one in ${_r##*/} (${_want:-none})" 4
                [ "$_built" = "$_head" ] || refuse "$_n was notarized from ${_built:-an unknown commit}, not HEAD $_head" 4
                if command -v spctl >/dev/null 2>&1; then
                    spctl -a -t open --context context:primary-signature "$_f" >/dev/null 2>&1 \
                        || refuse "spctl rejects $_n" 4
                else
                    refuse "spctl not found; publish runs on the Mac" 4
                fi ;;
            "${ZIP##*/}"|"${PDF##*/}"|"${MD##*/}") ;;
            *) [ -f "$HERE/$_n" ] || refuse "release/$_n is listed in assets.txt but missing" 1 ;;
        esac
    done

    # No release on a Friday afternoon (v5.6e was held on principle). HFS_ALLOW_FRIDAY=1 lifts it for
    # one run. HFS_FAKE_DOW and HFS_FAKE_HOUR stand in for the clock in extra/test/test_release_assets.py.
    _dow="${HFS_FAKE_DOW:-$(date +%u)}"; _hour="${HFS_FAKE_HOUR:-$(date +%H)}"
    if [ "$_dow" = 5 ] && [ "$_hour" -ge 12 ] && [ "${HFS_ALLOW_FRIDAY:-}" != 1 ]; then
        refuse "it is Friday afternoon; release another day, or set HFS_ALLOW_FRIDAY=1 for this one run" 6
    fi

    # The tag is absent, or already at HEAD (a publish that stopped after tagging). It is never moved.
    for _where in local origin; do
        _t="$(tag_target "$_where")"
        [ -z "$_t" ] || [ "$_t" = "$_head" ] \
            || refuse "tag $TAG already exists ($_where) at $_t, not HEAD; bump VERSION" 1
    done
    if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
        refuse "release $TAG already exists; releases are never replaced, so bump VERSION" 1
    fi
    WAY_BACK="$(gh release list --repo "$REPO" --exclude-drafts --limit 1 --json tagName --jq '.[0].tagName' 2>/dev/null || true)"
    [ -n "$WAY_BACK" ] || refuse "cannot read the previous release from GitHub (gh release list)" 6

    [ "$REFUSED" = 0 ] || exit 2
}

# The gate text publish --dry-run prints. Gilly gets it verbatim, for this tag only (RELEASING.md step 6).
dry_run_report() {
    echo "GATE: publish $TAG at $(cd "$SRC" && git rev-parse --short=12 HEAD) from master; way back: $WAY_BACK"
    _ci="$(asset_names ci)"
    for _n in $(asset_names all); do
        case "$_n" in
            "${ZIP##*/}"|"${PDF##*/}"|"${MD##*/}") echo "  (rebuilt by publish)  $_n" ;;
            *)  if [ -f "$HERE/$_n" ]; then
                    echo "  $(shasum -a 256 "$HERE/$_n" | awk '{ print $1 }')  $_n"
                elif printf '%s\n' "$_ci" | grep -qxF "$_n"; then
                    echo "  (attached by package.yml)  $_n"
                else
                    echo "  (not built; optional)  $_n"
                fi ;;
        esac
    done
    echo "guide will be regenerated"
}

publish() {
    # Refuse to touch an existing release. Overwriting one destroys the binaries someone may be
    # relying on, which is the whole thing per-release tags exist to prevent.
    if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
        echo "!! release $TAG already exists. Releases are immutable here: bump VERSION for a new one." >&2
        echo "   (To correct the newest release: with Gilly's yes, mark it superseded in its notes, fix, bump VERSION" >&2
        echo "   and publish the fix as a new release. Releases are never deleted; RELEASING.md, \"What ships\".)" >&2
        exit 2
    fi

    # Tag the commit this build actually came from. Without this the release tag says nothing
    # about the source: the preview's old single tag pointed at an upstream commit that was not
    # even an ancestor of the branch it shipped from.
    BUILD_SHA="$(cd "$SRC" && git rev-parse HEAD)"
    BUILD_BRANCH="$(cd "$SRC" && git rev-parse --abbrev-ref HEAD)"
    echo "==> tagging $TAG at $BUILD_SHA ($BUILD_BRANCH)"
    [ "$BUILD_BRANCH" = master ] || echo "   (warning: releases ship from master; this build is from '$BUILD_BRANCH')"

    # Exactly the local rows of assets.txt, gathered before tagging so a missing file cannot strand a
    # tag. A missing Intel dmg is skipped (the notes then point Intel Macs at the zip).
    set --
    for _n in $(asset_names local); do
        if [ -f "$HERE/$_n" ]; then
            set -- "$@" "$HERE/$_n"
        else
            case "$_n" in *-intel.dmg) ;; *) echo "!! release/$_n is missing; nothing was tagged" >&2; exit 2 ;; esac
        fi
    done

    # The notes are written, and they and the guide are scanned for em dashes, before the tag exists.
    SHA="$(shasum -a 256 "$ZIP" | awk '{print $1}')"
    NOTES="$(notes_file)"
    scan_em_dash tagged "$NOTES" "$MD"

    # A publish that stopped after tagging left the tag at this commit: reuse it, never move it.
    # preflight_publish has already refused a tag at any other commit.
    if [ "$(tag_target origin)" = "$BUILD_SHA" ]; then
        echo "   $TAG is already on origin at $BUILD_SHA; not tagging again"
    elif [ "$(tag_target local)" = "$BUILD_SHA" ]; then
        echo "   $TAG already exists here at $BUILD_SHA; pushing it"
        ( cd "$SRC" && git push origin "$TAG" )
    else
        ( cd "$SRC" && git tag -a "$TAG" "$BUILD_SHA" -m "$TITLE" && git push origin "$TAG" )
    fi

    echo "==> creating release $TAG"
    gh release create "$TAG" "$@" \
        --repo "$REPO" --title "$TITLE" --notes-file "$NOTES" $PRERELEASE
    rm -f "$NOTES"
    echo "==> done: https://github.com/$REPO/releases/tag/$TAG"
    echo "    gilly.space/heliofits-studio is a download page: it asks GitHub for the newest release itself."
}

# ---- macOS signing + notarization ------------------------------------------
# Produces a Gatekeeper-clean HelioFITS Studio.app (embedded JRE) inside a stapled
# .dmg. Config via env (or it auto-detects the first Developer ID it finds):
#   DEV_ID_APP     "Developer ID Application: NAME (TEAMID)"  (from: security find-identity -v -p codesigning)
#   NOTARY_PROFILE keychain profile name for notarytool        (default: jhv-notary, a legacy name; see RELEASING.md)
#   MAC_ARCH       arm64 (default) or x64. x64 builds the Intel Mac dmg, HFStudio-<v>-intel.dmg,
#                  from an Intel JDK (release/.jdk-x64, Temurin 25 for mac/x64, run under Rosetta):
#                  jlink and jpackage have to be the target architecture's. The Metal host
#                  dylib is already built for both architectures.
BUNDLE_ID="space.gilly.hfstudio"
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
# The submission in flight for this MAC_ARCH: written by notarize, read by notarize-resume, never
# removed by a script (git-ignored; the next submission for the same arch replaces it).
PENDING="$HERE/.notarize-pending.json"; [ "$MAC_ARCH" = x64 ] && PENDING="$HERE/.notarize-pending-intel.json"
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

# ---- notarization that cannot lie ------------------------------------------------------------
# 2026-09-23: `submit --wait` died mid-upload after printing an id, and a watcher read "does not
# exist" as "still queued" for 13 hours. So the submission runs without --wait and is believed only
# when notarytool says "Successfully uploaded file"; its id goes into a pending receipt; then
# `notarytool info` is polled every HFS_NOTARY_POLL_SECS (30) within HFS_NOTARY_LIMIT_SECS (600,
# the old --wait alarm). Accepted continues; Invalid or Rejected saves Apple's log and fails;
# "does not exist" is re-polled once after HFS_NOTARY_REPOLL_SECS (60, an estimate) and then fails.
# notarize-resume picks a pending id up again. HFS_NOTARY_WAIT=1 keeps the old --wait path for one
# release. The JSON field names (id, message, status) are notarytool's --output-format json names
# as documented, not yet seen in a real run here: pin them against the first real
# .notarize-submit.json and .notarize-info.json (RELEASING.md step 4).
NOTARY_POLL_SECS="${HFS_NOTARY_POLL_SECS:-30}"
NOTARY_REPOLL_SECS="${HFS_NOTARY_REPOLL_SECS:-60}"
NOTARY_LIMIT_SECS="${HFS_NOTARY_LIMIT_SECS:-600}"

# One top-level field of the JSON object on stdin, or nothing.
json_field() {
    python3 -c 'import json, sys
try:
    print(json.loads(sys.stdin.read()).get(sys.argv[1], ""))
except Exception:
    print("")' "$1"
}

# notarytool reports a locked screen as a missing profile (2026-09-18): name the real cause.
notary_explain() {
    if grep -qs 'No Keychain password item found' "$@"; then
        echo "!! screen locked? notarytool cannot read its keychain profile while the screen is locked; unlock and rerun (2026-09-18)" >&2
    fi
}

# Upload $DMG without waiting, and record the submission in $PENDING once the upload is complete.
notary_submit() {
    echo "==> submitting ${DMG##*/} to Apple (no --wait; the id is recorded before polling)"
    if ! perl -e 'alarm shift; exec @ARGV' 600 \
            xcrun notarytool submit "$DMG" --keychain-profile "$NOTARY_PROFILE" --output-format json \
            > "$HERE/.notarize-submit.json" 2> "$HERE/.notarize-submit.err"; then
        notary_explain "$HERE/.notarize-submit.err" "$HERE/.notarize-submit.json"
        echo "!! notarytool submit failed; its output is in release/.notarize-submit.err. Nothing is pending: rerun notarize." >&2
        exit 1
    fi
    _id="$(json_field id < "$HERE/.notarize-submit.json")"
    _msg="$(json_field message < "$HERE/.notarize-submit.json")"
    [ -n "$_id" ] || { echo "!! notarytool submit printed no id; see release/.notarize-submit.json" >&2; exit 1; }
    case "$_msg" in
        *"Successfully uploaded"*) ;;
        *) echo "!! submission $_id reports '$_msg', not 'Successfully uploaded file': an id alone does not mean the upload finished (2026-09-23). Rerun notarize." >&2
           exit 1 ;;
    esac
    cat > "$PENDING" <<EOF
{
  "submission_id": "$_id",
  "dmg_sha256": "$(shasum -a 256 "$DMG" | awk '{print $1}')",
  "build_sha": "$(cd "$SRC" && git rev-parse HEAD)",
  "arch": "$MAC_ARCH",
  "submitted_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF
    echo "   submitted $_id; pending receipt ${PENDING##*/}"
}

# Poll submission $1 until Apple accepts it (return 0) or the answer is a failure (exit 1).
notary_poll() {
    _id="$1"; _waited=0; _missing=0
    while :; do
        perl -e 'alarm shift; exec @ARGV' 60 \
            xcrun notarytool info "$_id" --keychain-profile "$NOTARY_PROFILE" --output-format json \
            > "$HERE/.notarize-info.json" 2> "$HERE/.notarize-info.err" || true
        _status="$(json_field status < "$HERE/.notarize-info.json")"
        case "$_status" in
            Accepted)
                echo "   $_id: Accepted"
                return 0 ;;
            Invalid|Rejected)
                perl -e 'alarm shift; exec @ARGV' 120 \
                    xcrun notarytool log "$_id" --keychain-profile "$NOTARY_PROFILE" "$HERE/.notarize-log-$_id.json" \
                    > /dev/null 2>&1 || true
                echo "!! submission $_id is $_status; Apple's log: release/.notarize-log-$_id.json" >&2
                exit 1 ;;
            "In Progress")
                _missing=0 ;;
            *)
                if grep -qsi 'does not exist' "$HERE/.notarize-info.err" "$HERE/.notarize-info.json"; then
                    if [ "$_missing" = 1 ]; then
                        echo "!! submission $_id does not exist, after a re-poll: the upload never completed (2026-09-23). Rerun ./deploy_release.sh notarize." >&2
                        exit 1
                    fi
                    _missing=1
                    echo "   $_id: does not exist; one re-poll in ${NOTARY_REPOLL_SECS}s"
                    sleep "$NOTARY_REPOLL_SECS"; _waited=$((_waited + NOTARY_REPOLL_SECS))
                    continue
                fi
                notary_explain "$HERE/.notarize-info.err"
                echo "!! notarytool info $_id gave no status (release/.notarize-info.err). ${PENDING##*/} stays; rerun ./deploy_release.sh notarize-resume." >&2
                exit 1 ;;
        esac
        if [ "$_waited" -ge "$NOTARY_LIMIT_SECS" ]; then
            echo "!! $_id is still In Progress after ${_waited}s. ${PENDING##*/} stays; run ./deploy_release.sh notarize-resume later." >&2
            exit 1
        fi
        echo "   $_id: In Progress (${_waited}s)"
        sleep "$NOTARY_POLL_SECS"; _waited=$((_waited + NOTARY_POLL_SECS))
    done
}

# Staple, validate and write the receipt for the dmg built from commit $1. Shared by notarize and
# notarize-resume.
staple_and_receipt() {
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
  "build_sha": "$1",
  "build_revision": "$(cd "$SRC" && git rev-list --count "$1")",
  "notarized_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF
    echo "==> receipt written: ${RECEIPT##*/}"
}

# Finish a notarization recorded in $PENDING: after a crash, a locked screen, or a poll that ran out
# of time. Refuses (exit 2) unless the dmg is the exact file that was submitted.
notarize_resume() {
    : "${NOTARY_PROFILE:=jhv-notary}"
    [ -f "$PENDING" ] || { echo "!! no ${PENDING##*/}: nothing is pending for MAC_ARCH=$MAC_ARCH; run notarize" >&2; exit 2; }
    _id="$(receipt_key "$PENDING" submission_id)"
    _want="$(receipt_key "$PENDING" dmg_sha256)"
    _built="$(receipt_key "$PENDING" build_sha)"
    [ -n "$_id" ] && [ -n "$_want" ] && [ -n "$_built" ] \
        || { echo "!! ${PENDING##*/} lacks submission_id, dmg_sha256 or build_sha" >&2; exit 2; }
    [ -f "$DMG" ] || { echo "!! no ${DMG##*/}: submission $_id was for a dmg that is not here" >&2; exit 2; }
    _have="$(shasum -a 256 "$DMG" | awk '{print $1}')"
    [ "$_have" = "$_want" ] \
        || { echo "!! ${DMG##*/} changed since submission $_id (sha256 $_have, submitted $_want); run notarize again" >&2; exit 2; }
    echo "==> resuming notarization $_id for ${DMG##*/}"
    notary_poll "$_id"
    staple_and_receipt "$_built"
}

notarize_mac() {
    # Offline, before anything is built or sent to Apple: the bundled ffmpeg is the GPL build its
    # manifest names, and the licence notices match the jars.
    echo "==> checking the ffmpeg and licence manifests"
    ( cd "$SRC" && python3 extra/ffmpeg/update_ffmpeg.py --check && python3 extra/licenses/sync_licenses.py --check ) \
        || { echo "!! the ffmpeg or licence manifest check failed; nothing was built or sent" >&2; exit 1; }
    notarize_preconditions

    echo "==> building a fresh jar + dylib"
    ( cd "$SRC" && ant clean jar build-metal-host >/dev/null )
    [ -f "$SRC/$DYLIB" ] || { echo "!! $DYLIB missing after build"; exit 1; }

    APPSTAGE="$HERE/.app_stage"; OUT="$HERE/.app_out"; ENT="$HERE/entitlements.plist"
    rm -rf "$OUT"

    # Everything jpackage bundles is the classpath: the main jar and the dependency jars (the
    # manifest Class-Path points at lib/), with only this platform's natives (stage-app.sh).
    ( cd "$SRC" && "$HERE/stage-app.sh" "$STAGE_PLATFORM" "$APPSTAGE" )

    # Inject the native dylib into the main jar at the resource path AngleLibraries reads,
    # because a .app runs with cwd=/ so the cwd-relative lib/natives-macos lookup can't
    # fire; the classpath-resource fallback is the only cwd-independent path.
    tmp="$(mktemp -d)"; mkdir -p "$tmp/$ARCH_RES"; cp "$SRC/$DYLIB" "$tmp/$ARCH_RES/"
    ( cd "$tmp" && "$JAVA_HOME/bin/jar" uf "$APPSTAGE/HFStudio.jar" "$ARCH_RES/$(basename "$DYLIB")" )
    rm -rf "$tmp"

    # Hardened-runtime entitlements, from the tracked release/entitlements.plist: the JVM JITs, and
    # natives get extracted from jars, so library validation is relaxed. Main executable only.
    [ -f "$ENT" ] || { echo "!! $ENT is missing" >&2; exit 1; }

    echo "==> signing native libraries inside the bundled jars"
    sign_jar_natives "$APPSTAGE"

    echo "==> jlinking the trimmed runtime from $JAVA_HOME"
    # Passed explicitly: JAVA_HOME may have been chosen in this script (MAC_ARCH=x64, or found by
    # notarize_preconditions) rather than exported by the caller.
    JAVA_HOME="$JAVA_HOME" "$HERE/make-runtime.sh" "$HERE/.runtime"
    echo "==> jpackage app-image (embeds the trimmed runtime)"
    "$JAVA_HOME/bin/jpackage" \
        --type app-image --name "$BUNDLE_NAME" --app-version "$APP_VERSION" \
        --input "$APPSTAGE" --main-jar HFStudio.jar \
        --main-class org.helioviewer.jhv.HFStudio \
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

    # The icon, a second time, as a compiled asset catalog. jpackage only knows about --icon, and a
    # bundle carrying just a .icns is drawn on a grey plate at 16 and 32 pixels by macOS 26: every
    # application on this machine with a catalog fills the frame there, every .icns-only one does
    # not. This has to happen before signing, since it puts a file inside the bundle.
    echo "==> compiling the icon asset catalog"
    ACTOOL="$(xcrun --find actool 2>/dev/null || command -v actool || true)"
    "$ACTOOL" --version --output-format xml1 >/dev/null 2>&1 \
        || { echo "!! actool does not run. It comes with Xcode, not the command line tools:"
             echo "   install Xcode, then: sudo xcode-select -s /Applications/Xcode.app/Contents/Developer"; exit 1; }
    CATOUT="$HERE/.appicon"; rm -rf "$CATOUT"; mkdir -p "$CATOUT/Assets.xcassets"
    cp -R "$APPICONSET" "$CATOUT/Assets.xcassets/"
    "$ACTOOL" --compile "$CATOUT" --app-icon AppIcon --platform macosx \
        --minimum-deployment-target 11.0 --output-partial-info-plist "$CATOUT/partial.plist" \
        "$CATOUT/Assets.xcassets" >/dev/null
    [ -f "$CATOUT/Assets.car" ] || { echo "!! actool produced no Assets.car"; exit 1; }
    cp "$CATOUT/Assets.car" "$APP/Contents/Resources/Assets.car"
    /usr/libexec/PlistBuddy -c "Add :CFBundleIconName string AppIcon" "$APP/Contents/Info.plist" 2>/dev/null \
        || /usr/libexec/PlistBuddy -c "Set :CFBundleIconName AppIcon" "$APP/Contents/Info.plist"
    rm -rf "$CATOUT"
    echo "   Assets.car in the bundle, CFBundleIconName set"

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
    # Signed with exactly the tracked entitlements, and every Mach-O in the bundle and in its jars
    # carries the team and the hardened runtime, before a dmg is built or Apple is asked.
    codesign -d --entitlements - --xml "$APP" 2>/dev/null | python3 -c '
import plistlib, sys
data = sys.stdin.buffer.read()
got = plistlib.loads(data) if data.strip() else None
want = plistlib.load(open(sys.argv[1], "rb"))
if got != want:
    sys.exit("!! the app was signed with entitlements %r; release/entitlements.plist says %r" % (got, want))
' "$ENT"
    "$HERE/verify_signatures.sh" "$APP"

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

    if [ "${HFS_NOTARY_WAIT:-}" = 1 ]; then
        # The path before submissions were recorded, kept for one release in case polling misreads Apple.
        echo "==> notarizing with submit --wait (HFS_NOTARY_WAIT=1)"
        # macOS has no `timeout(1)`; perl's alarm is always present. Guard the wait so a hung
        # connection to Apple fails the attempt instead of blocking forever.
        perl -e 'alarm shift; exec @ARGV' 600 \
            xcrun notarytool submit "$DMG" --keychain-profile "$NOTARY_PROFILE" --wait
    else
        notary_submit
        notary_poll "$(receipt_key "$PENDING" submission_id)"
    fi
    staple_and_receipt "$(cd "$SRC" && git rev-parse HEAD)"

    rm -rf "$APPSTAGE" "$OUT"
    echo "==> done: $DMG  ($(du -h "$DMG" | awk '{print $1}'))"
    echo "    publish attaches it to the release automatically when it exists."
}

case "$MODE" in
    package)  build_guide; repackage ;;
    guide)    build_guide --strict; scan_em_dash uploaded "$MD"; upload_guide_only ;;
    publish)  preflight_publish
              if [ -n "$DRY_RUN" ]; then dry_run_report; exit 0; fi
              approve_publish
              build_guide --strict; repackage; publish ;;
    notarize) notarize_mac ;;
    notarize-resume) notarize_resume ;;
    notes)    SHA="$(if [ -f "$ZIP" ]; then shasum -a 256 "$ZIP" | awk '{print $1}'; else echo '(zip not built)'; fi)"
              _notes="$(notes_file)"; cat "$_notes"; rm -f "$_notes" ;;
    assets)   case "${2:-all}" in local|ci|all) asset_names "${2:-all}" ;; *) echo "usage: $0 assets [local|ci|all]" >&2; exit 2 ;; esac ;;
    *) echo "usage: $0 {package|guide|publish [--dry-run]|notarize|notarize-resume|notes|assets [local|ci|all]}"; exit 2 ;;
esac
