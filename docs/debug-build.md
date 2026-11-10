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
   `~/hfs-rc/debug/studio-app/HelioFITS Studio.app` (replaced every run) and `~/hfs-rc/HFStudio-<version>-debug-<hash>.dmg`.
4. Quits every earlier debug copy (any app under `~/hfs-rc`, any process whose home is `~/HFStudio-debug*`; never the
   installed app or `~/HFStudio`), launches the one new one
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

## Other apps

`release/debug-build.sh --target <name>` works for any repository: it clones that repository itself, takes its default
branch (`master` or `main`) as the base, and uses `release/debug/<name>.sh` for what is specific. The driver stays in
this repository; the other repositories need nothing.

| Target | Repo | Debug data | Status |
|---|---|---|---|
| `studio` | HelioFITS-Studio | `~/HFStudio-debug` (`HFS_HOME`) | built, launched, data folder checked |
| `pyhelio` | pyHelioFITS, the Cache Manager app | `~/HelioFITS-debug` (library via `-libraryPath`; own bundle id, so own settings; no `hfcore://` scheme; the core is the clone's own code in a venv) | built and launched, 47 s |
| `peek` | HelioFITS-Peek | none | recipe written from RELEASING.md, **never built or launched**; see below |

Peek is sandboxed, shares settings with its extensions through an app group, and running a debug copy registers
its extensions with LaunchServices (the recurring generic-icon thumbnail bug in RELEASING.md). So its debug build
does not isolate settings, and after trying it you quit it and run `./lsclean.sh` in the clone.

A new target is a file setting `TARGET_REPO`, `TARGET_NAME`, `TARGET_DATA`, `TARGET_ENV` (env var that moves its
data), `TARGET_ARGS` (launch arguments), `TARGET_READY` (a command that succeeds once it is up) and defining
`target_build <clone> <outdir> <tag>`, which leaves `<outdir>/<TARGET_NAME>.app` built from the clone.
