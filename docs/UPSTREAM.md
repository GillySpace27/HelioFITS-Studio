# Upstream JHelioviewer

HelioFITS Studio is an unofficial fork of JHelioviewer
(<https://github.com/Helioviewer-Project/JHelioviewer-SWHV>). This file says how the two histories
relate and how changes move between them. `CLAUDE.md` ("## Upstream") points here.

## Remotes

```sh
git remote add upstream https://github.com/Helioviewer-Project/JHelioviewer-SWHV.git
git remote set-url --push upstream no-push    # fetch only; never push to upstream
git fetch upstream
```

`origin` is `GillySpace27/HelioFITS-Studio`. Upstream pull requests go from branches on Gilly's
GitHub fork of JHelioviewer, never from this repository's master.

## How master relates to upstream

Master's history was rewritten on 2026-09-16 (fork assets purged), and the plan behind this file
feared that git no longer saw upstream inside master. Measured on `origin/master` (full history, not
a shallow clone) against `upstream/master` at `6b874317543f` (2026-09-30), the ancestry is intact:

```sh
git merge-base origin/master upstream/master    # 3c1620aa12d5, "Coalesce contiguous JPIP response segments", 2026-09-14
git log -1 --format=%P a0f731ad4                # 756ab86de... 3c1620aa1...: "Merge JHelioviewer through 14 September 2026"
git log --oneline 3c1620aa1..upstream/master | wc -l    # 55 upstream commits since
```

`3c1620aa1` is an upstream commit, with the same id upstream and here, so no ancestry merge
(`git merge -s ours`) is needed and none was made. The plan's `d1d6610c9` is not an object in
upstream as of that fetch. A shallow clone cannot answer the merge-base question (it prints nothing
and exits 1): run `git fetch --unshallow origin master` first. Run the first command above in your
own checkout too: the plan behind this file recorded `ea5893037`, a 2020 commit, from a checkout whose
local master may differ from `origin/master`.

A dry run of the sync on 2026-10-02, `git merge-tree --write-tree --name-only origin/master
upstream/master`, reports 37 conflicted files (four are the tracked `lib/jhv/jhv-natives-*.jar`
files, which are binary and cannot be merged as text). It writes objects only.

## What is not taken from upstream (decided 2026-10-24)

Upstream's JPEG 2000 and JPIP line is not merged or cherry-picked into this fork, and its commits
do not count as "behind". In October 2026 upstream moved reading to the esajpip C client (CDDL 1.0)
in a native bridge, `native/jpeg2000/jhv_j2k.{h,cc}`, with Kakadu decoding behind it. This fork
decodes with OpenJPEG and reads JPIP with its own Java data-bin cache (`view/j2k`, since
ece2db71 on 2026-09-16), because Kakadu's licence does not reach a fork. The two stacks cannot be
cherry-picked across. About 60 of the 234 upstream commits measured on 2026-10-23 are this line,
plus the half of upstream's layer refactor (`Frames`, `FrameDecoder`, `Source`) that exists to
serve it.

Exceptions: a fix in that line that has an equivalent in this fork's reader is ported by hand,
as 658173e8 (bare ampersands in XML headers) was into `Jp2Boxes`.

The alternative, adopting upstream's bridge with an OpenJPEG decoder behind it, is written up and
shelved in `docs/upstream-j2k-bridge-plan.md`. Reopen it only if this fork's JPIP code starts
costing real bugs, and then as one sync that takes the layer refactor with it.

Also not taken: deletions of files this fork keeps or replaced (`release/`, `JHelioviewer.java`,
`lib/jhv/jhv-natives-*.jar`, `EventCache`, `SWEKDownloader`), and upstream's native packaging.

Deferred, not refused: upstream's move to WCSLIB (0d9c2ea7) and its image-geometry split. Port
them after the sunpy decode-parity harness exists, so the harness shows what changed.

## Measuring the gap

Rerun monthly and record the result here:

```sh
git fetch upstream
git merge-base origin/master upstream/master
git rev-list --count --no-merges "$(git merge-base origin/master upstream/master)"..upstream/master
git merge-tree --write-tree --name-only origin/master upstream/master | grep -c '^CONFLICT'
```

| Date | upstream/master | Behind | Dry-run conflicts |
|---|---|---|---|
| 2026-09-30 | 6b874317543f | 55 | (not measured) |
| 2026-10-02 | | | 37 |
| 2026-10-23 | 4f0dd068 | 234 | 128 |

Cherry-picked on 2026-10-24 (branch `sync/upstream-picks-2026-10-24`): dd00d8ad, e2e8a84c,
9b89c4e7, d6690677, 990c8343, 148262f1, 9351e775, 3de99722, ac7e861c, f12f9aa6, 4c68e0ac and
29882143 (both with conflicts resolved by hand), with b4eb8a68 and 658173e8 ported by hand. Not
taken: 6b874317 (fast Rice for short and integer FITS), which conflicts with this fork's own
PUNCH Rice decoder in `FastRiceProvider`; merging two "values unchanged" rewrites needs a
bit-for-bit comparison on real files first (`FrameBench --rice`, then the parity harness).

## Syncing upstream into master

1. `git fetch upstream`, then a branch: `git switch -c sync/upstream-<yyyy-mm-dd> origin/master`.
2. `git merge --no-ff upstream/master`. Resolve conflicts keeping the fork's behaviour; where
   upstream fixed something the fork also changed, keep both when they do not overlap.
3. `ant check-all` must pass.
4. Merge the branch into master with Gilly's yes, and push only on his yes.

Never `git rebase` master onto upstream, never `git push --force`, never `filter-repo`.

## Cherry-picking instead

Cherry-pick (`git cherry-pick -x <sha>`) instead of merging when:

- the dry run above reports conflicts you would rather resolve one fix at a time;
- the upstream change touches licensing: a new dependency, a vendored library, a file's licence
  header, or `LICENSE*`. Each is reviewed on its own because MPL 2.0 is file-level copyleft and
  the fork's licence inventory (`extra/licenses/`) must be updated with it
  (`python3 extra/licenses/sync_licenses.py --check`);
- only one fix is wanted before the next full sync.

## Preparing an upstream pull request

- Branch from `upstream/master`, never from master: `git switch -c pr/<topic> upstream/master`.
- Make each new pull-request branch independent of the open ones: never base one on another,
  never rebase or force-push an open one. The open set is listed in Gilly's vault
  (projects/jhelioviewer.md).
- Carry only upstream-relevant changes: no fork names, no `HFStudio` paths, no fork-owned files
  (below), no `CLAUDE.md`.
- Opening the pull request is Gilly's action.

## Fork-owned files

`docs/fork-owned-files.txt` lists the files under `src/` that master added and upstream does not
have. They never conflict in a sync and never go into an upstream pull request. Regenerate it
after each sync:

```sh
{
    printf '# Files under src/ that exist in this fork and not in upstream JHelioviewer.\n'
    printf '# Generated by the command in docs/UPSTREAM.md ("Fork-owned files"); do not edit by hand.\n'
    printf '# upstream/master was %s\n' "$(git rev-parse --short=12 upstream/master)"
    git diff --name-status --diff-filter=A upstream/master HEAD -- src | cut -f2 | LC_ALL=C sort
} > docs/fork-owned-files.txt
```

## Before a large refactor

Run `git log upstream/master -1 -- <path>` for each upstream file the refactor touches
(`Actions.java`, `MenuBar.java`, `ToolBar.java`, `ImageLayer.java`, `State.java` are frequent). If
upstream changed it recently, prefer an additive change: a new fork-owned class beside it and a
small hook in it, so the next sync conflicts on a few lines instead of the whole file.
