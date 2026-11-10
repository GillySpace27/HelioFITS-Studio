#!/bin/bash
# Debug build: merge every open PR that is green and conflict-free onto a throwaway branch, build an unsigned
# app, launch it against its own data folder, and say what is in it. For looking at what the current work does,
# not for shipping: it never tags, releases, notarizes, signs with the Developer ID, pushes, or touches master.
#
#   release/debug-build.sh [--target studio] [--dry-run] [--no-launch] [--only 12,15] [--skip 20]
#
# --dry-run   select and merge, print the report, build nothing.   --only/--skip  PR numbers, comma separated.
# Work happens in $HFS_DEBUG_ROOT (default ~/hfs-rc/debug): a clone, the branch rc/debug (local only, rebuilt from
# origin/master every run), the app, a log, REPORT.md. The app is replaced each run; the data folder
# (TARGET_DATA, default ~/HFStudio-debug, set in release/debug/<target>.sh) is not.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TARGET=studio DRY=0 LAUNCH=1 ONLY="" SKIP="" EXTRA=""
while [ $# -gt 0 ]; do
    case "$1" in
        --target) TARGET=$2; shift ;;
        --dry-run) DRY=1 ;;
        --no-launch) LAUNCH=0 ;;
        --only) ONLY=",$2,"; shift ;;
        --skip) SKIP=",$2,"; shift ;;
        --extra) EXTRA=$2; shift ;;   # a branch on origin to merge first (this tooling, before it is on master)
        *) echo "usage: $0 [--target studio] [--dry-run] [--no-launch] [--only N,N] [--skip N,N] [--extra BRANCH]" >&2; exit 2 ;;
    esac
    shift
done
[ -f "$HERE/debug/$TARGET.sh" ] || { echo "!! no release/debug/$TARGET.sh" >&2; exit 2; }
# shellcheck source=/dev/null
. "$HERE/debug/$TARGET.sh"
command -v gh >/dev/null && gh auth status >/dev/null 2>&1 || { echo "!! gh is not signed in" >&2; exit 2; }
ROOT="${HFS_DEBUG_ROOT:-$HOME/hfs-rc/debug}"; CLONE="$ROOT/$TARGET-clone"; REPORT="$ROOT/REPORT-$TARGET.md"
mkdir -p "$ROOT" "$HOME/hfs-rc"

[ -d "$CLONE/.git" ] || git clone --quiet "https://github.com/$TARGET_REPO.git" "$CLONE"
cd "$CLONE"
git merge --abort 2>/dev/null || true
git fetch --quiet origin
MAIN=$(git remote show origin | sed -n 's/.*HEAD branch: //p')   # master for Studio, main for Peek and pyHelioFITS
git checkout --quiet -f -B rc/debug "origin/$MAIN"
git clean -fdq -e 'release/.runtime' >/dev/null 2>&1 || true
BASE=$(git rev-parse --short HEAD)
if [ -n "$EXTRA" ]; then
    git fetch --quiet origin "$EXTRA" && git merge --no-ff --no-edit -q FETCH_HEAD >/dev/null || { echo "!! --extra $EXTRA did not merge" >&2; exit 1; }
fi

# One line per open PR: number, then OK or the reason it is skipped.
INC="" SKP=""
while IFS=$'\t' read -r n sha why title; do
    case "$ONLY" in ,*) case "$ONLY" in *",$n,"*) ;; *) continue ;; esac ;; esac
    case "$SKIP" in *",$n,"*) why="skipped on request" ;; esac
    if [ "$why" = OK ]; then
        git fetch --quiet origin "pull/$n/head"
        if git merge --no-ff --no-edit -q -m "debug: merge PR $n" FETCH_HEAD >/dev/null 2>&1; then
            INC="$INC$n\t${sha:0:9}\t$title\n"; continue
        fi
        git merge --abort 2>/dev/null || git reset --quiet --hard HEAD
        why="conflicts with $MAIN plus the PRs merged before it"
    fi
    SKP="$SKP$n\t$why\t$title\n"
done < <(gh pr list --repo "$TARGET_REPO" --state open --limit 100 \
    --json number,title,headRefOid,isDraft,mergeable,baseRefName,statusCheckRollup | python3 -c '
import json, sys
for p in sorted(json.load(sys.stdin), key=lambda p: p["number"]):
    bad, wait = [], []
    for c in p["statusCheckRollup"] or []:
        name = c.get("name") or c.get("context")
        res = c.get("conclusion") or c.get("state") or ""
        if c.get("status", "COMPLETED") != "COMPLETED" or res in ("PENDING", "EXPECTED", ""): wait.append(name)
        elif res not in ("SUCCESS", "SKIPPED", "NEUTRAL"): bad.append(name)
    why = "OK"
    if p["baseRefName"] != sys.argv[1]: why = "base is " + p["baseRefName"]
    elif p["isDraft"]: why = "draft"
    elif p["mergeable"] == "CONFLICTING": why = "conflicts with " + sys.argv[1]
    elif p["mergeable"] != "MERGEABLE": why = "GitHub has not computed mergeability yet"
    elif bad: why = "CI failed: " + ", ".join(sorted(set(bad)))
    elif wait: why = "CI not finished: " + ", ".join(sorted(set(wait)))
    print("%d\t%s\t%s\t%s" % (p["number"], p["headRefOid"], why, p["title"].replace("\t", " ")))
' "$MAIN")
TAG=$(git rev-parse --short=9 HEAD)

{
    echo "# Debug build $TARGET $TAG  ($(date '+%Y-%m-%d %H:%M %Z'))"
    echo "origin/$MAIN $BASE; branch rc/debug is local and was never pushed."
    echo; echo "## Included"; [ -n "$INC" ] && printf %b "$INC" | awk -F'\t' '{printf "- #%s `%s` %s\n",$1,$2,$3}' || echo "- (none)"
    echo; echo "## Skipped"; [ -n "$SKP" ] && printf %b "$SKP" | awk -F'\t' '{printf "- #%s %s: %s\n",$1,$3,$2}' || echo "- (none)"
    echo; echo "## What to click"
    for n in $(printf %b "$INC" | cut -f1); do
        echo "- #$n: $(gh pr view "$n" --repo "$TARGET_REPO" --json title -q .title)"
        # Raw material only: checkbox lines and the "Not verified" section, i.e. what nobody has looked at yet.
        # The click list itself is written by whoever reads this with the diff beside it (docs/debug-build.md).
        gh pr view "$n" --repo "$TARGET_REPO" --json body -q .body | awk '/^## /{nv=($0 ~ /Not verified/)} nv && /^- /{print "    " $0} /^[ ]*- \[[ x]\]/{print "    " $0}' | head -10
    done
} > "$REPORT"

if [ "$DRY" = 1 ]; then cat "$REPORT"; echo; echo "(dry run: nothing built)"; exit 0; fi

LOG="$ROOT/build-$TAG.log"
echo "==> building $TARGET $TAG (log: $LOG)"
target_build "$CLONE" "$ROOT/$TARGET-app" "$TAG" >"$LOG" 2>&1 || { tail -25 "$LOG"; echo "!! build failed; full log $LOG" >&2; exit 1; }
APP="$ROOT/$TARGET-app/$TARGET_NAME.app"

if [ "$LAUNCH" = 1 ]; then
    # Quit every earlier debug copy, not just this bundle: older builds under ~/hfs-rc, and worktree dev runs that
    # were given a debug home. Matched on command line plus environment (ps eww); never /Applications or ~/HFStudio.
    stale() { ps eww -axo pid=,command= | grep -E "$HOME/hfs-rc/|HFS_HOME=$TARGET_DATA|user\.home=$TARGET_DATA|user\.home=$HOME/HFStudio-debug" \
        | grep -E "HFStudio|HelioFITS Studio" | grep -v -E "grep|ps eww" | awk -v me=$$ '$1 != me {print $1}'; }
    pids=$(stale || true); [ -z "$pids" ] || { echo "==> quitting stale debug copies: $(echo $pids)"; kill $pids 2>/dev/null || true; }
    for _ in $(seq 10); do [ -z "$(stale)" ] && break; sleep 1; done
    pids=$(stale || true); [ -z "$pids" ] || kill -9 $pids 2>/dev/null || true
    mkdir -p "$TARGET_DATA"
    open -n ${TARGET_ENV:+--env "$TARGET_ENV"} "$APP" ${TARGET_ARGS:+--args $TARGET_ARGS}
    for _ in $(seq 20); do sleep 2; pgrep -f "$APP/Contents" >/dev/null && { [ -z "${TARGET_READY:-}" ] || eval "$TARGET_READY" >/dev/null 2>&1; } && break; done
    pgrep -f "$APP/Contents" >/dev/null && echo "launched, data in $TARGET_DATA" >> "$REPORT" \
        || { echo "!! the app did not stay up; see $LOG and $TARGET_DATA/Logs" >&2; cat "$REPORT"; exit 1; }
fi
cat "$REPORT"
