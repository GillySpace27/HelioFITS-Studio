# Archive

Files kept for the record that are no longer part of HelioFITS Studio. Nothing here is built, packaged, or read by the application. Files were moved with `git mv`, so `git log --follow <path>` still shows where each one came from.

| Folder | Contents | Why it is here |
|---|---|---|
| `upstream/` | Files inherited from JHelioviewer (ESA and the Royal Observatory of Belgium; mostly by Bogdan Nicula): its README, build notes, contributor guide, Coverity and FindBugs configuration, the Gradle wrapper, the design definition file and its pandoc build, the architecture and traceability PDFs, the JHelioviewer user manual, one-off sources and tables from `extra/`, the 2016 website and splash screen, and JHelioviewer's own macOS and Windows packaging. | They describe or package the upstream product rather than HelioFITS Studio. They are kept for attribution and reference. The MPL 2.0 licence stays in the repository root, and the About dialog carries the third-party credits. |
| `preview/` | The README and field guide PDF of the PUNCH and coronagraph preview build. | Superseded by the HelioFITS Studio releases. |
| `reviews/` | The QA polish review of 2026-09-14 and the codex-loop release-readiness report. `THIRD-PARTY.md`, the licence inventory of every bundled component taken on 2026-09-01, is not tracked on master: its committed copy is at the root of the `demo-all` branch (`git show origin/demo-all:THIRD-PARTY.md`, added in 7cdaa35f), and on 2026-09-28 an untracked copy sat at `archive/reviews/THIRD-PARTY.md` in the worktree `starfield-c3-punch-direction-27f1ae`. | Point-in-time reviews. The inventory predates the Kakadu removal and the replacement of the Intel macOS FFmpeg build, so parts of it no longer describe the tree. |
| `branch-notes/` | Design specs, implementation plans, PR description drafts and verification notes that only ever existed on feature branches, filed under the branch they came from. | The features shipped; these record how they were planned. |
| `tools/ffmpeg-root-copy/` | `update_ffmpeg.py`, `ffmpeg.json`, `update_ffmpeg.md` (from `extra/`) and `test_update_ffmpeg.py` (from `extra/test/`): the older root copy of the FFmpeg updater, its build record, its notes and its test, moved with `git mv` on 2026-10-02. Two tracked texts still name the old paths and were left as written: `resources/licenses/FFmpeg.txt` line 4 and `extra/licenses/README.md` line 65. | A drifted duplicate of `extra/ffmpeg/`, the copy the maintenance suite runs: this one wrote `FFmpeg-GPL.txt`, the live one writes `FFmpeg-Notices.txt` and `GPL-3.0.txt`. The moved test no longer runs from here (it imports `update_ffmpeg` from its parent folder). |

## Removed instead of archived

- `extra/release-resources/mac/swhv.keychain`, JHelioviewer's macOS code-signing keychain. A signing keychain does not belong in a source tree. It is still in history: `git show archive/branch/demo-all:extra/release-resources/mac/swhv.keychain`.
- `extra/__pycache__/update_ffmpeg.cpython-312.pyc`, a compiled Python cache file.

## The rest of the history

Before the September 2026 consolidation, every local branch, every branch on `origin`, and the one stash were tagged `archive/branch/<name>`, `archive/origin/<name>` and `archive/stash/punch-integration-wip-2026-06-15`. `git tag -l 'archive/*'` lists them.
