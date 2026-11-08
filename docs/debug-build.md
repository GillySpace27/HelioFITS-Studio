# Debug build

`release/debug-build.sh` answers one question: *what would a user get if they tried the work that is open right
now?* It is not a release path. It never tags, publishes, notarizes, signs with the Developer ID, pushes, or
merges to `master`, and `release.sh` refuses to run if its switch (`HFS_UNSIGNED`) is set.

## Use

```bash
release/debug-build.sh              # merge the good PRs, build, launch, print the report
release/debug-build.sh --dry-run    # select and merge only; print the report
release/debug-build.sh --skip 226   # leave a PR out;  --only 224,226 for just those
release/debug-build.sh --no-launch  # build, do not open
```

Say "build and launch the test build" and an agent runs the first line, then reads `REPORT.md` and the diffs and
gives you the click list (below).

## What it does

1. Clones the repository once into `~/hfs-rc/debug/<target>-clone`, then each run resets the local branch
   `rc/debug` to `origin/master`. `rc/debug` is never pushed.
2. Takes every open PR whose base is `master`, which is not a draft, which GitHub calls mergeable, and whose
   checks have all finished green (skipped and neutral are fine). Merges them in PR-number order with
   `--no-ff`; one that conflicts with the ones before it is aborted and listed as skipped. Skipped PRs are
   listed with the reason (draft, CI failed or unfinished, conflicts, wrong base, left out by you).
3. Builds an unsigned app with `HFS_UNSIGNED=1 release/deploy_release.sh notarize`: the same jar, runtime,
   icon, bundled core and launch smoke test as a release, ad-hoc signed, nothing sent to Apple. Output:
   `~/hfs-rc/debug/app/HelioFITS Studio.app` (replaced every run) and `~/hfs-rc/HFStudio-<version>-debug-<hash>.dmg`.
4. Quits the previous debug app only (matched by its path, never the installed one), launches the new one
   against the debug data folder, and checks it stays up and wrote a log there.
5. Writes `~/hfs-rc/debug/REPORT.md` and prints it: included PRs with the merged head hash, skipped PRs and why,
   and for each included PR its checkbox lines and "Not verified" bullets from the body.

## The debug data folder

The app bundle is thrown away every build; your settings, sessions, templates and cache are not. The debug
build keeps all of them in `~/HFStudio-debug` (set `HFS_DEBUG_HOME` to move it), a folder of its own that the
real `~/HFStudio` never sees and that no rebuild touches.

How: `Directories.HOME` is `~/HFStudio`, and everything that lives in it (Settings, States, FileCache,
Exports, Logs, Templates, Models, `storage.properties`) follows. If the environment variable `HFS_HOME`
(or the property `-Dhfs.home`) is set, that folder is `HOME` instead, and the one-time copy of an older
install's settings is skipped, so the debug folder starts empty. The launcher passes it with
`open --env`, and `Session.restart` inherits the environment. Delete the folder to start from nothing.

Not covered by `HFS_HOME`: the FITS library (HelioFITS Core's own store, chosen under Choose Folder...)
and anything the user points Settings > Storage at. A debug build that is given the same library as the real
one will read and write it; give it its own in Settings if that matters.

## The click list

The script supplies raw material (titles, the "Not verified" bullets, checkboxes). The click list is the
reader's: open the diff, name each visible change, and give it as steps, one line each, for example
"Press Ctrl+1: left section 1 folds". Anything the PR says was not checked in a real window goes first.

## Another app (Peek, pyHelioFITS)

A target is one file, `release/debug/<name>.sh`, setting `TARGET_REPO`, `TARGET_NAME`, `TARGET_DATA`,
`TARGET_ENV` (the env var that moves its data folder) and defining
`target_build <clone> <outdir> <tag>` that leaves `<outdir>/<TARGET_NAME>.app` built from the clone.
`debug-build.sh --target <name>` does the rest. The driver assumes `master` as the base branch and a
macOS app; copy it into the other repository with its own `release/debug/` for now.
