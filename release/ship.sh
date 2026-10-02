#!/bin/sh
# RELEASING.md as phases that stop at the first red row. Run from anywhere in the checkout.
#
#   release/ship.sh bump <version>   write VERSION (no trailing newline); changelog.md needs its heading
#   release/ship.sh prepare          tracker green through `pushed`; ant clean check-all; notarize (and the
#                                    Intel dmg when release/.jdk-x64 exists); package; tracker rows
#   release/ship.sh gate             publish --dry-run, the strict guide build, the smoke test typed as
#                                    done, and the gate text to paste to Gilly
#   release/ship.sh publish          deploy_release.sh publish, after the exact tag is typed at a terminal;
#                                    with no terminal, only when HFS_PUBLISH_APPROVED is that exact tag
#   release/ship.sh confirm          wait (bounded) for package.yml on the tag, then the tracker
#
# Refusals exit 2, failures 1 (deploy_release.sh's convention). Nothing here pushes a branch; `publish`
# pushes the tag and creates the release, which is why it needs Gilly's yes for that tag every time.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "$HERE/.." && pwd)"
STATUS="$HERE/skills/ship-hfstudio/scripts/status.py"
VERSION_PATTERN='^[0-9]+(\.[0-9]+){0,2}$'   # the pattern deploy_release.sh checks

version() { tr -d '[:space:]' < "$SRC/VERSION"; }

# Exit 1 at the first of the named tracker rows that is not PASS, printing it.
require_rows() {
    python3 "$STATUS" --json --done "${DONE:-}" | python3 -c '
import json, sys
rows = {m["key"]: m for m in json.load(sys.stdin)["milestones"]}
for key in sys.argv[1:]:
    m = rows[key]
    if m["result"] != "PASS":
        print("!! stopped at %s: %s: %s (%s)" % (key, m["label"], m["result"], m["detail"]), file=sys.stderr)
        if m["how"]:
            print("   next: " + m["how"].replace("\n", "\n         "), file=sys.stderr)
        sys.exit(1)
    print("   ok   %s: %s" % (key, m["detail"]))
' "$@"
}

# The terminal the human types at, or nothing when there is none (a script, an agent, < /dev/null).
tty_in() { [ -t 0 ]; }

bump() {
    _v="${1:-}"
    printf '%s\n' "$_v" | grep -qE "$VERSION_PATTERN" \
        || { echo "!! '$_v' is not a version like 0.8.4 (jpackage needs it numeric)" >&2; exit 2; }
    awk -v v="$_v" 'index($0, "## ") == 1 && index($0, " " v " ") > 0 { found = 1 } END { exit !found }' "$SRC/changelog.md" \
        || { echo "!! changelog.md has no '## ... $_v ...' heading; write the section first (publish takes the notes from it)" >&2; exit 2; }
    # printf, not echo: a trailing newline in VERSION once ended the jar manifest early (2026-09-18).
    printf '%s' "$_v" > "$SRC/VERSION"
    echo "VERSION is now $_v. Commit it with the source (RELEASING.md step 1); pushing master needs Gilly's yes."
}

prepare() {
    echo "==> the source is committed and pushed (RELEASING.md step 1)"
    require_rows pushed
    echo "==> ant clean check-all"
    ( cd "$SRC" && ant clean check-all )
    if [ -z "${JAVA_HOME:-}" ] && [ -d /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home ]; then
        JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home; export JAVA_HOME
    fi
    [ -n "${JAVA_HOME:-}" ] || { echo "!! set JAVA_HOME to Temurin 25 (not Homebrew openjdk@25; RELEASING.md step 4)" >&2; exit 2; }
    echo "==> notarize (Apple Silicon)"
    "$HERE/deploy_release.sh" notarize
    if [ -d "$HERE/.jdk-x64" ]; then
        echo "==> notarize (Intel, from release/.jdk-x64)"
        MAC_ARCH=x64 "$HERE/deploy_release.sh" notarize
    else
        echo "   no release/.jdk-x64: no Intel dmg this time (optional; the notes send Intel Macs to the zip)"
    fi
    echo "==> package (guide + zip)"
    "$HERE/deploy_release.sh" package
    require_rows pushed jar dylib guide dmg zip
    echo "prepare: done. Next: release/ship.sh gate"
}

gate() {
    _gate="$(mktemp "${TMPDIR:-/tmp}/hfs-gate-XXXXXX")"
    "$HERE/deploy_release.sh" publish --dry-run > "$_gate" || { _rc=$?; cat "$_gate"; exit "$_rc"; }
    ( cd "$HERE" && python3 build_guide.py --strict )
    cat <<EOF

Smoke test (RELEASING.md step 5), on the dmg, not the jar:
  1. Quit every running HelioFITS Studio (a second instance cannot take the JPIP cache lock).
  2. Open release/HFStudio-$(version).dmg and start HelioFITS Studio from the mounted image.
  3. Load a JPEG 2000 layer and a FITS layer; both draw.
  4. Export a short movie; it plays.
  5. Quit through the menu.
EOF
    tty_in || { echo "!! the smoke test is typed as done at a terminal; there is none" >&2; exit 2; }
    printf 'Type smoke-tested when all five are done: '
    read -r _answer
    [ "$_answer" = smoke-tested ] || { echo "!! not smoke-tested; nothing else ran" >&2; exit 2; }
    DONE=smoketest require_rows pushed jar dylib guide dmg zip smoketest
    echo
    echo "Paste this to Gilly verbatim and ask for a yes for $(sed -n '1s/^GATE: publish \([^ ]*\) .*/\1/p' "$_gate") only:"
    cat "$_gate"
}

publish() {
    _tag="v$(version)"
    if [ -n "${HFS_PUBLISH_APPROVED:-}" ]; then
        [ "$HFS_PUBLISH_APPROVED" = "$_tag" ] \
            || { echo "!! HFS_PUBLISH_APPROVED is '$HFS_PUBLISH_APPROVED', not $_tag; a yes is for one tag" >&2; exit 2; }
    elif tty_in; then
        printf 'Gilly said yes to publishing exactly this tag. Type it (%s): ' "$_tag"
        read -r _answer
        [ "$_answer" = "$_tag" ] || { echo "!! typed '$_answer', not $_tag; nothing published" >&2; exit 2; }
    else
        echo "!! no terminal: publish runs only with HFS_PUBLISH_APPROVED=$_tag set for this one invocation, after Gilly's yes in chat" >&2
        exit 2
    fi
    "$HERE/deploy_release.sh" publish
    echo "publish: done. Next: release/ship.sh confirm"
}

confirm() {
    _tag="v$(version)"
    _repo="$(sed -n 's/^REPO="\([^"]*\)"$/\1/p' "$HERE/deploy_release.sh")"
    _limit="${HFS_CONFIRM_MINUTES:-30}"; _m=0
    echo "==> waiting up to ${_limit} min for package.yml on $_tag"
    while :; do
        _state="$(gh run list --repo "$_repo" --workflow package.yml --event release --limit 20 \
                    --json headBranch,status --jq ".[] | select(.headBranch == \"$_tag\") | .status" 2>/dev/null | head -1 || true)"
        [ "$_state" = completed ] && break
        [ "$_m" -lt "$_limit" ] || { echo "!! package.yml for $_tag is '${_state:-not started}' after ${_limit} min; rerun confirm later" >&2; exit 1; }
        sleep 60; _m=$((_m + 1))
    done
    python3 "$STATUS" --done smoketest
    DONE=smoketest require_rows published live ci_package assets_all
}

case "${1:-}" in
    bump)    bump "${2:-}" ;;
    prepare) prepare ;;
    gate)    gate ;;
    publish) publish ;;
    confirm) confirm ;;
    *) echo "usage: $0 {bump <version>|prepare|gate|publish|confirm}" >&2; exit 2 ;;
esac
