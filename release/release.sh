#!/bin/bash
# One-command HelioFITS Studio release: preflight, confirm once, fresh clone, build, sign, notarize, parity,
# smoke, tag + publish, wait for package.yml, site PR + merge, verify.
#
#   release/release.sh <version> [--auto-smoke] [--resume] [--skip-intel]
#
# RUN THIS YOURSELF IN TERMINAL.APP, not through an agent. It needs a real terminal (it refuses without one)
# and asks ONE question: type the tag. That typed tag is Gilly's yes for this tag only, for the Apple
# upload, the tag push, the release, and the site PR merge. The script then hands the existing per-step
# gates (ship.sh, deploy_release.sh) their approval itself, so nothing is set by an agent and no approval
# environment variable is needed from outside. Never run it, or set HFS_*_APPROVED, from a Claude session.
#
# Needs on origin/master before you start: VERSION = <version> and a changelog.md heading for it (the
# version-bump PR, merged by Gilly). Nothing here pushes a branch; it pushes the tag, creates the release,
# and merges the site PR for that tag.
#
# State: $HFS_RELEASE_HOME (default ~/hfs-release) holds one clone per version and a .state file. After a
# failure, fix the cause and run the same command with --resume: finished steps are skipped. The clone is
# fresh from origin/master, on branch master, so a dirty or stale main checkout can never leak in.
# ~/vscode/Website is never read or written; the site work happens in its own clone (SITE=).
#
# --check       run only the read-only preflight (safe for an agent; sends and builds nothing), then exit.
# --auto-smoke  accept release/smoke_dmg.sh (starts the dmg, draws, no uncaught exception) as the smoke test
#               instead of asking you to do the five manual steps. Declared up front, shown in the confirmation.
# Exit: 0 done, 1 a step failed (rerun with --resume), 2 refused.
set -euo pipefail

REPO=GillySpace27/HelioFITS-Studio
SITE_REPO=GillySpace27/GillySpace27.github.io
HOME_DIR="${HFS_RELEASE_HOME:-$HOME/hfs-release}"
FEED_URL=https://gilly.space/heliosoftware/feed/heliofits-studio.json

VERSION_ARG="" AUTO_SMOKE=0 RESUME=0 SKIP_INTEL=0 CHECK_ONLY=0
for a in "$@"; do
    case "$a" in
        --auto-smoke) AUTO_SMOKE=1 ;;
        --resume) RESUME=1 ;;
        --skip-intel) SKIP_INTEL=1 ;;
        --check) CHECK_ONLY=1 ;;
        -*) echo "usage: $0 <version> [--auto-smoke] [--resume] [--skip-intel]" >&2; exit 2 ;;
        *) VERSION_ARG="$a" ;;
    esac
done
printf '%s\n' "$VERSION_ARG" | grep -qE '^[0-9]+(\.[0-9]+){0,2}$' \
    || { echo "usage: $0 <version, like 0.9.1> [--auto-smoke] [--resume] [--skip-intel]" >&2; exit 2; }
V="$VERSION_ARG"; TAG="v$V"
CLONE="$HOME_DIR/studio-$V"; SITE_CLONE="$HOME_DIR/site-$V"; STATE="$HOME_DIR/$V.state"
mkdir -p "$HOME_DIR"; touch "$STATE"

die()  { echo "!! $*" >&2; exit 2; }
fail() { echo "!! $*" >&2; echo "   fix it, then: release/release.sh $V --resume" >&2; exit 1; }
done_step() { grep -qx "$1" "$STATE"; }
mark()      { echo "$1" >> "$STATE"; }
step() {  # step <name> <function>
    if [ "$RESUME" = 1 ] && done_step "$1"; then echo "==> $1: already done, skipping"; return; fi
    echo; echo "==> $1"; "$2"; mark "$1"
}
sha() { shasum -a 256 "$1" | awk '{print $1}'; }

# ---- 0. refuse what must not run here --------------------------------------------------------------------
{ [ "$CHECK_ONLY" = 1 ] || { [ -t 0 ] && [ -t 1 ]; }; } || die "needs a real terminal (Terminal.app). It is Gilly's one yes per release; an agent must not run it."
[ "$CHECK_ONLY" = 1 ] || [ -z "${CLAUDECODE:-}${CLAUDE_CODE_ENTRYPOINT:-}" ] || die "run from Terminal.app, not from inside a Claude Code session"
[ "$(uname -s)" = Darwin ] || die "runs on Gilly's Mac (signing identity, notarization)"
unset HFS_PUBLISH_APPROVED HFS_NOTARIZE_APPROVED HFS_SMOKE_APPROVED HFS_GUIDE_APPROVED

# ---- 1. preflight (cheap, before anything is built or sent) -----------------------------------------------
preflight() {
    [ "$(date +%u)" != 5 ] || [ "$(date +%H)" -lt 12 ] || [ "${HFS_ALLOW_FRIDAY:-}" = 1 ] \
        || die "Friday after 12:00: deploy_release.sh refuses. Another day, or HFS_ALLOW_FRIDAY=1"
    for t in git gh ant python3 xcrun hdiutil shasum curl; do command -v "$t" >/dev/null || die "missing tool: $t"; done
    gh auth status >/dev/null 2>&1 || die "gh is not signed in (gh auth login)"
    # NWRA's authenticated proxy stalls git/gh (75 s each) and blocks the LASCO background fetch parity needs.
    # Java ignores proxy env vars, so a system proxy is what matters; this is a heuristic (not tested here).
    if scutil --proxy 2>/dev/null | grep -Eq '(HTTP|HTTPS|ProxyAutoConfig)Enable : 1' && [ "${HFS_ALLOW_PROXY:-}" != 1 ]; then
        die "a system proxy is on (NWRA network?). Parity's LASCO download fails behind it. Leave that network, or HFS_ALLOW_PROXY=1 to try anyway"
    fi
    export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home}"
    [ -x "$JAVA_HOME/bin/jpackage" ] || die "JAVA_HOME must be Temurin 25 ($JAVA_HOME), not Homebrew openjdk"
    security find-identity -v -p codesigning | grep -q 'Developer ID Application' || die "no Developer ID Application identity (screen unlocked? keychain?)"
    xcrun notarytool history --keychain-profile "${NOTARY_PROFILE:-jhv-notary}" >/dev/null 2>&1 \
        || die "notarytool profile '${NOTARY_PROFILE:-jhv-notary}' unusable (screen locked, or re-run notarytool store-credentials)"
    PY="${HELIAD_PYTHON:-$HOME/Documents/NWRA/heliad/.venv/bin/python}"
    "$PY" -c 'import sunpy, astropy' >/dev/null 2>&1 \
        || die "parity needs a Python with sunpy, astropy and the core: set HELIAD_PYTHON (now: $PY)"
    [ -d "$HOME/HeliadLibrary" ] || die "parity needs ~/HeliadLibrary"
    pgrep -fq 'HelioFITS Studio|HFStudio' && die "quit every running HelioFITS Studio first (JPIP cache lock; smoke test)"
    gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1 && die "release $TAG already exists; bump VERSION"
    _mv="$(gh api "repos/$REPO/contents/VERSION?ref=master" --jq .content | base64 -d | tr -d '[:space:]')"
    [ "$_mv" = "$V" ] || die "origin/master VERSION is '$_mv', not $V: merge the version-bump PR first"
    [ -n "${HFS_X64_JDK:-}" ] || HFS_X64_JDK="$HOME/Documents/NWRA/PUNCH_Science/JHelioviewer-SWHV/release/.jdk-x64"
    if [ "$SKIP_INTEL" = 0 ] && [ ! -d "$HFS_X64_JDK" ]; then
        die "no Intel JDK at $HFS_X64_JDK (set HFS_X64_JDK), or pass --skip-intel to ship without the Intel dmg on purpose"
    fi
    echo "   preflight ok"
}

# ---- 2. the one confirmation ----------------------------------------------------------------------------
confirm_once() {
    cat <<EOT

About to release HelioFITS Studio $V from a fresh clone of origin/master. Typing the tag authorizes, for
$TAG only: the upload to Apple, the tag push, the GitHub release, and merging the site PR on gilly.space.
Smoke test: $([ "$AUTO_SMOKE" = 1 ] && echo "AUTOMATED (smoke_dmg.sh stands in for the five manual steps)" || echo "you will be asked to do the five manual steps and type smoke-tested")
Intel dmg: $([ "$SKIP_INTEL" = 1 ] && echo "SKIPPED on purpose" || echo "built first, from $HFS_X64_JDK")
EOT
    printf 'Type the tag to go ahead (%s): ' "$TAG"; read -r ans
    [ "$ans" = "$TAG" ] || die "typed '$ans', not $TAG; nothing was sent anywhere"
}

# ---- 3. fresh clone on master ----------------------------------------------------------------------------
fresh_clone() {
    [ ! -e "$CLONE" ] || die "$CLONE exists (a previous attempt). Rerun with --resume, or move it aside yourself"
    git clone --branch master "https://github.com/$REPO.git" "$CLONE"
    [ "$(git -C "$CLONE" rev-parse HEAD)" = "$(git -C "$CLONE" rev-parse origin/master)" ] || fail "clone is not at origin/master"
    [ "$(tr -d '[:space:]' < "$CLONE/VERSION")" = "$V" ] || fail "VERSION in the clone is not $V"
    [ "$SKIP_INTEL" = 1 ] || ln -s "$HFS_X64_JDK" "$CLONE/release/.jdk-x64"
    git -C "$CLONE" rev-parse HEAD > "$HOME_DIR/$V.head"
}

approvals() { export HFS_NOTARIZE_APPROVED="$TAG" HFS_PUBLISH_APPROVED="$TAG" HFS_GUIDE_APPROVED="$TAG"
              [ "$AUTO_SMOKE" = 1 ] && export HFS_SMOKE_APPROVED="$TAG" || true; }

# ---- 4. check, build, sign, notarize -------------------------------------------------------------------
build() {
    cd "$CLONE"
    approvals
    ant clean check-all
    # Intel first: each notarize rebuilds the jar, and parity pins the jar, so the arm64 build must be the last one.
    if [ "$SKIP_INTEL" = 0 ]; then ( cd release && MAC_ARCH=x64 ./deploy_release.sh notarize ); fi
    ( cd release && ./deploy_release.sh notarize )
    ( cd release && ./deploy_release.sh package )
    sha "$CLONE/HFStudio.jar" > "$HOME_DIR/$V.jarsha"
    echo "   jar frozen: $(cat "$HOME_DIR/$V.jarsha")"
}

# ---- 5. parity on the frozen jar ----------------------------------------------------------------------------
parity() {
    cd "$CLONE"
    [ "$(sha HFStudio.jar)" = "$(cat "$HOME_DIR/$V.jarsha")" ] || fail "HFStudio.jar changed since the build; rerun from the build step"
    HELIAD_PYTHON="$PY" release/parity.sh
    [ "$(sha HFStudio.jar)" = "$(cat "$HOME_DIR/$V.jarsha")" ] || fail "HFStudio.jar changed during parity"
    python3 release/parity.py verify "release/HFStudio-$V-parity.json" --jar HFStudio.jar --commit "$(git rev-parse HEAD)" \
        --version "$V" --manifest release/parity-manifest.json || fail "parity report does not vouch for this jar"
}

# ---- 6. gate (dry run, strict guide, smoke) ------------------------------------------------------------------
gate() { cd "$CLONE"; approvals; release/ship.sh gate; }

# ---- 7. publish --------------------------------------------------------------------------------------------
publish() {
    cd "$CLONE"; approvals
    [ "$(git rev-parse HEAD)" = "$(cat "$HOME_DIR/$V.head")" ] || fail "clone HEAD moved"
    [ "$(sha HFStudio.jar)" = "$(cat "$HOME_DIR/$V.jarsha")" ] || fail "jar changed after parity; refusing to publish"
    if [ ! -d "$SITE_CLONE" ]; then git clone "https://github.com/$SITE_REPO.git" "$SITE_CLONE"; fi
    git -C "$SITE_CLONE" fetch -q origin && git -C "$SITE_CLONE" checkout -q master && git -C "$SITE_CLONE" reset -q --hard origin/master
    # SITE points deploy_release.sh's feed step at the clean clone; ~/vscode/Website is never touched.
    SITE="$SITE_CLONE" release/ship.sh publish
}

# ---- 8. Windows/Linux packages, then the site PR and its merge ----------------------------------------------
packages() { cd "$CLONE"; release/ship.sh confirm || fail "package.yml not green yet (windows-sign, linux). Check the Actions run, then --resume"; }

site() {
    cd "$SITE_CLONE"
    git status --porcelain | grep -q . || fail "no feed record in the site clone; run: (cd $CLONE/release && SITE=$SITE_CLONE ./deploy_release.sh feed)"
    python3 tools/release_pr.py --product heliofits-studio --version "$V"
    _pr="$(gh pr list --repo "$SITE_REPO" --state open --search "heliofits-studio $V" --json number --jq '.[0].number')"
    [ -n "$_pr" ] || fail "no site PR found for $V; open it by hand from $SITE_CLONE"
    echo "   site PR #$_pr"
    for _ in $(seq 1 20); do
        gh pr checks "$_pr" --repo "$SITE_REPO" --watch >/dev/null 2>&1 && break
        sleep 15
    done
    gh pr merge "$_pr" --repo "$SITE_REPO" --squash
}

# ---- 9. verify what the world sees -------------------------------------------------------------------------
verify() {
    cd "$CLONE"
    _live=""
    for _ in $(seq 1 30); do   # gilly.space deploys within a few minutes of the merge
        curl -fsS "$FEED_URL" 2>/dev/null | grep -q "\"$V\"" && { _live=1; break; }
        sleep 30
    done
    [ -n "$_live" ] || fail "$FEED_URL does not list $V after 15 min"
    VDIR="$HOME_DIR/verify-$V-$(date +%s)"; mkdir -p "$VDIR"   # fresh per run, so nothing is overwritten
    for n in $(release/deploy_release.sh assets all); do
        [ -f "release/$n" ] || { echo "   (no local copy of $n: CI asset or optional)"; continue; }
        gh release download "$TAG" --repo "$REPO" --pattern "$n" --dir "$VDIR"
        [ "$(sha "$VDIR/$n")" = "$(sha "release/$n")" ] || fail "uploaded $n differs from the local, notarized file"
    done
    for d in "$VDIR"/*.dmg; do
        [ -f "$d" ] || continue
        xcrun stapler validate "$d" >/dev/null || fail "$d: no stapled ticket"
        spctl -a -t open --context context:primary-signature "$d" 2>&1 | grep -q accepted || fail "$d: Gatekeeper rejects it"
    done
    python3 release/skills/ship-hfstudio/scripts/status.py --done smoketest
    echo; echo "RELEASE $TAG DONE: https://github.com/$REPO/releases/tag/$TAG"
}

if [ "$CHECK_ONLY" = 1 ]; then preflight; echo "preflight passed for $TAG"; exit 0; fi
if [ "$RESUME" = 0 ]; then
    [ ! -s "$STATE" ] || die "$STATE has finished steps from an earlier attempt: rerun with --resume, or move it aside"
    preflight
    confirm_once
else
    preflight; confirm_once   # approvals are per run: resuming asks again, once
fi
step clone fresh_clone
step build build
step parity parity
step gate gate
step publish publish
step packages packages
step site site
step verify verify
