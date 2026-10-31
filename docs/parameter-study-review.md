# Parameter study: review notes for Gilly

Branch `claude/study` (local, nothing pushed, master untouched). Worktrees under `.claude/worktrees/`.
Package branches, each merged into `claude/study`: study-view (WP1), study-model (WP2),
study-contact-sheet (WP5), study-runner (WP3), study-panel (WP4), study-movies (WP6),
study-sweeps (WP7), study-queue (WP8). Design: https://claude.ai/artifact/Y6APbfEhsNFrWZQoZF1Fou

## What each package does

- **WP1 view**: `Commands.getView / setView / restoreView / clearViewOverride`. A row's view is applied without an undo step or a dirty session; layer visibility is a draw-time override like Compare.
- **WP2 model**: `study` package (Study, Variant, StudyOutput, StudyIO, NamePattern). `*.hfstudy.json` round-trips unknown keys, refuses a newer version; `study` key in the session only when a study exists.
- **WP5 contact sheet**: `ContactSheet.grid` and `PanelLabel`.
- **WP3 runner**: `StudyRunner` (Export set for stills). Per variant: setView, settle, Screenshot, copy to run folder, sidecar `.hfrec.json`; `manifest.hfstudy.json` and a copy of the job; overwrite policy always / skip-if-unchanged (fingerprint of scene sha, resolved view, time) / never; cancel keeps written files. Headless: `-state scene.jhv -study job.hfstudy.json -out folder`, exit codes as `-export`. `StudyRunner.commandLine` for Copy command. The per-PNG sidecar is written by the runner itself because PR #87's sidecar writer is not in this branch.
- **WP4 panel**: Movie > Study... (table, inherit/override cells, live preview on select, Save this view Cmd+Alt+R, update/apply/duplicate/delete/drag reorder/paste, validation, count line).
- **WP6 movies**: pinned `movies` block (`MovieBlock`, seeded from Record settings), `MovieRunner` (one recording at a time, your playback and recording settings restored), `TileMovies` (off by default). Time sweep plus movies is refused with a message. `ExportMovie.startStudy` gives the exact long side without the Quick Share 1920 cap.
- **WP7 sweeps**: time sweep (list, start/step/count, master frames) and field sweep (`warpLambda`, `fov`, `zoom`, inherit-only rule), count and size estimate, run limit `study.runLimit` default 500 with a confirmation.
- **WP8 queue**: `JobQueue` (one at a time; queued/running/done/failed/cancelled; cancel, re-run, open folder), Queue... window, Load preset (lambda ladder: Helioradial 1, 0, -1 and Unrolled 0), Copy command, SAMP `jhv.study.run` and `jhv.study.status` (JSON in `job` and in the completion `output`; `Commands.studyRun / studyStatus` so an MCP wrapper can come later). Export set now queues.

## Test results (all run by me on this branch)

- `ant check-all`: checks 198 passed, 0 failed, 0 skipped; check-all 8 ok, 0 failed, 3 skipped (the three known-red legacy suites).
- Real headless runs (app started with a throwaway home, `HFS_STUDY_E2E=1`, AIA 171 test FITS): `StudyRunnerCheck` (two variants: two different PNGs, two sidecars, one manifest with matching sha256, second run writes nothing) and `MovieRunnerCheck` (two MP4s in the manifest with matching hashes) both pass. These two print a `skip` line without that variable, so plain `ant test` does not run them; CI would need `HFS_STUDY_E2E=1` under xvfb.
- Guards unchanged (204/21/50/317).

## Not verified

- Nothing on screen: the Study panel, queue window, preview and restore on the real canvas, the Layers chip editor, drag reorder, paste from the clipboard, whether Cmd+Alt+R fires twice, Load preset menu.
- Time sweep against a live multi-frame layer (`frames: master`) and the seek-render loop for sweeps; field sweep combined with movies (movies ignore field sweeps and make one per row).
- The movie test is a one-frame movie (single FITS); a real multi-frame range and TileMovies with ffmpeg were not run.
- `size` in stills is not applied yet (stills use the current Record size); `study.runLimit` setting read is untested; no Pizza column (no pizza code in this branch); contact sheet is not yet called by the runner.
- Seven commits carry a model name in the trailer (my own and the sweeps agent's earlier ones); later ones do not. Reword before any push if you want it gone.

## How to try it

```sh
cd .claude/worktrees/study && ant jar
JAVA_TOOL_OPTIONS="-Duser.home=$(mktemp -d)" ./run.sh -load extra/test/data/syn_AIA_171_2026-01-12T00-00-00_f_V3.fits
```
Then Movie > Study..., Load preset, Export set, watch the Queue window. Headless:
```sh
JAVA_TOOL_OPTIONS="-Duser.home=$(mktemp -d)" ./run.sh -load <fits or -state scene.jhv> -study resources/studies/lambda-ladder.hfstudy.json -out /tmp/ladder
```
`lib/natives-macos/libjhvmetalhost.dylib` is rebuilt in the worktrees and never committed.
