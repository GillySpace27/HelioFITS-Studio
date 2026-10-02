#!/bin/sh
# Read-only report on this repository's worktrees, for deciding which ones to retire. It suggests;
# it never removes, prunes, moves, checks out or commits anything. Retiring a worktree is Gilly's call.
#
#   extra/tools/worktree_report.sh                # each worktree: branch, ahead/behind master,
#                                                 # commits not in master by content, uncommitted paths
#   extra/tools/worktree_report.sh --release-dir  # also: release/*.dmg, release/*.zip and root *.jar
#                                                 # not named for VERSION, with sizes (all git-ignored)
#
# "Unique" uses git cherry, so a commit whose patch is already in master does not count: a branch
# can be ahead and still hold nothing new. Uncommitted changes are counted separately; a worktree
# with any of them is never suggested for retirement.
set -eu
cd "$(dirname "$0")/../.." || exit 1
base="${BASE:-master}"
version="$(tr -d '[:space:]' < VERSION)"

git worktree list --porcelain | awk '/^worktree / { print substr($0, 10) }' | while IFS= read -r wt; do
    echo "$wt"
    if [ ! -d "$wt" ]; then
        echo "  directory missing (git worktree list calls it prunable); nothing to read"
        continue
    fi
    tip="$(git -C "$wt" rev-parse HEAD)"
    branch="$(git -C "$wt" symbolic-ref -q --short HEAD || echo "detached at $(git -C "$wt" rev-parse --short HEAD)")"
    ahead="$(git rev-list --count "$base..$tip")"
    behind="$(git rev-list --count "$tip..$base")"
    unique="$(git cherry "$base" "$tip" | grep -c '^+' || true)"
    changes="$(git -C "$wt" status --short | wc -l | tr -d ' ')"
    echo "  branch $branch; $ahead ahead of $base, $behind behind; $unique unique; $changes uncommitted paths"
    git cherry -v "$base" "$tip" | grep '^+' | sed 's/^+ /    unique: /' || true
    if [ "$unique" = 0 ] && [ "$changes" = 0 ]; then
        echo "  suggestion: holds nothing unmerged; a candidate for Gilly to retire"
    else
        echo "  suggestion: keep; it holds unmerged or uncommitted work"
    fi
done

if [ "${1:-}" = --release-dir ]; then
    echo "binaries not named for VERSION $version (git-ignored; this script never moves them):"
    for f in release/*.dmg release/*.zip ./*.jar; do
        [ -f "$f" ] || continue
        case "$f" in
            *"-$version."*|*"-$version-"*|./HFStudio.jar) continue ;;
        esac
        echo "  $(du -h "$f" | cut -f1)  $f"
    done
fi
exit 0
