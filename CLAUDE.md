# CLAUDE.md

Working notes for agents (and anyone new) in the HelioFITS Studio repository. Read this first.
`release/RELEASING.md` is authoritative for releases; it is linked here, never copied. This file is
fork-only: never include it in a pull request to `Helioviewer-Project/JHelioviewer-SWHV`.

## Identity

People read **HelioFITS Studio**. The technical name stays **HFStudio**, so no released user has
anything to move. Never rename any of these:

| What | Value | Set in |
|---|---|---|
| Display name | HelioFITS Studio | `src/org/helioviewer/jhv/app/AppInfo.java` (`programName`) |
| Jar | `HFStudio.jar` | `build.xml` |
| Main class | `org.helioviewer.jhv.HFStudio` | `build.xml` (`Main-Class`), `release/deploy_release.sh` (`--main-class`) |
| Data folder | `~/HFStudio` | `src/org/helioviewer/jhv/io/Directories.java` (`NAME`) |
| macOS bundle id | `space.gilly.hfstudio` | `release/deploy_release.sh` (`BUNDLE_ID`) |
| SAMP name | `HFStudio` | `src/org/helioviewer/jhv/io/samp/SampClient.java` (`samp.name`) |
| Release assets | `HFStudio-<version>.*` and the rest of `release/assets.txt` | `release/assets.txt` |
| Repository | `GillySpace27/HelioFITS-Studio` | `REPO` in `release/deploy_release.sh` |
| Download page | `https://gilly.space/heliofits-studio` | site repo `GillySpace27/GillySpace27.github.io` |

An unofficial fork of JHelioviewer (ESA and the Royal Observatory of Belgium, MPL 2.0). The MPL grants
no trademark; keep the fork clearly unofficial (README, "Licence, source code and bundled
components").

## Build and check

Java 25 and Apache Ant. `notarize` needs Temurin 25, not Homebrew `openjdk@25` (RELEASING.md step 4).

```sh
ant jar                    # kernels, compile (-Xlint:all), build-metal-host on macOS, HFStudio.jar
ant test                   # compile, then extra/run-checks.sh; last line "checks: N passed, 0 failed"
ant prone                  # Error Prone, run by hand
python3 extra/test/run_tests.py              # legacy suites: maintenance model j2k timelines event
python3 -m unittest discover -s extra/ffmpeg
python3 -m unittest discover -s extra/licenses
python3 extra/test/test_release_assets.py    # release asset list and publish refusals
```

Run one check (classpath as in `.github/workflows/checks.yml`):

```sh
CP="bin:extra/test-classes:resources:$(find lib -name '*.jar' | tr '\n' ':')"
java -Djava.awt.headless=true -cp "$CP" org.helioviewer.jhv.app.SessionStateArgCheck
```

Writing a check: a plain class at the top level of `extra/test/` (`run-checks.sh` compiles
`extra/test/*.java` only), named `<Something>Check`, whose `package` line is the package of the code
it reaches. `main` prints `  ok   <what>` or `  FAIL <what>` per assertion and exits nonzero on any
FAIL; it prints `SKIP: <reason>` when it cannot run here. A check that touches `Settings` sets
`user.home` to a temp directory first, as `SessionStateArgCheck` does: a check once overwrote
Gilly's settings file (`Settings.java`, the comment above `loaded`). Show every new check failing
once before making it pass. Fixtures go in `extra/test/data/` with a row in its `README.md`.

`ant clean` deletes `lib/natives-macos/libjhvmetalhost.dylib`, which is tracked: `ant jar` rebuilds
it on macOS. Never commit a changed copy (see "Must-nots").

## Running the dev build safely

Run the jar you just built, with a throwaway home, so nothing touches Gilly's `~/HFStudio`
(settings, sessions, autosave, cache):

```sh
ant jar
JAVA_TOOL_OPTIONS="-Duser.home=$(mktemp -d)" ./run.sh
```

That is what `extra/launch-shot.sh` does on CI. Never launch `/Applications/HelioFITS Studio.app`
and never `open -a "HelioFITS Studio"`: both start the installed release, not your build
(2026-08-27). `ant run` cannot pass arguments and runs against the real home. Only one instance may
use a given home: a second one cannot take the JPIP ehcache lock and then every image read fails,
which looks like a rendering bug (RELEASING.md step 5). `kill -TERM` does not autosave; quit through
the menu when the session matters.

## Must-nots

Each with its source (vault paths are Gilly's notes in `research-tasks`).

- Delete nothing. Retire a file with `git mv` into `archive/` plus a row in `archive/README.md`;
  retire a branch with an annotated tag `archive/branch/<name>` and leave the branch. Never `git rm`,
  `git push --force`, rewrite history, or delete a branch, tag, release, release asset, cache or
  settings key. (Gilly, 2026-09-28; `archive/README.md`.)
- Never push, publish, notarize for release, edit a release or run a workflow without Gilly's yes
  for that one action; a yes for one release never carries to the next. Publish only after
  `./deploy_release.sh publish --dry-run` and his yes to its output (RELEASING.md step 6;
  vault `daily_notes/2026-08-24.md:67-69`).
- Never `--clobber` a release asset (only the guide-only mode does, by design). Never delete the
  `v5.6.0-punch-preview` release: a poster QR code encodes it. Keep `v5.6a-coronal-research`.
  (vault `projects/jhelioviewer.md:5`, `:470`.)
- Hand out `https://github.com/GillySpace27/HelioFITS-Studio/releases`, never `/releases/latest`
  (it skips pre-releases). gilly.space paths are case-sensitive: hand out lowercase only
  (vault `projects/jhelioviewer.md:5`, `:561-564`).
- Never create a GitHub repository named `HFStudio` or `PUNCHStudio`: it ends GitHub's forwarding
  that 0.8.0 to 0.8.2 update checks rely on (RELEASING.md "Legacy names").
- No em dashes (U+2014) in anything you write: code, comments, UI strings, docs, release notes,
  commit messages. The ones already in upstream `src/` comments are Gilly's call; leave them
  (vault `projects/jhelioviewer.md:330`).
- Never ship the nonfree FFmpeg; the FFmpeg notices and `extra/ffmpeg/ffmpeg.json` stay consistent
  (`extra/ffmpeg/README.md`; vault `projects/jhelioviewer.md:263-287`). Kakadu was replaced by
  OpenJPEG; never add a `*kdu*` library (README, "Licence, source code and bundled components").
- Never commit a changed `lib/natives-macos/libjhvmetalhost.dylib` (vault `projects/jhelioviewer.md:1043`).
- Never run `git filter-repo` or any other history rewrite: it would rewrite about 14,000 commits,
  strip signatures and detach the fork from upstream (vault `daily_notes/2026-09-05.md:10-12`).
- No release on a Friday afternoon; `publish` refuses unless `HFS_ALLOW_FRIDAY=1`
  (vault `daily_notes/2026-09-11.md:113-115`).
- Loading data must never depend on how the scene is drawn; selecting layers must never change the
  imagery; a data channel never passes through a colour table (vault `projects/jhelioviewer.md:402`,
  `:422-425`; `daily_notes/2026-09-01.md:64-65`).
- RHEF output is visualization, "not a calibrated radiance". Instrument facts come from a source,
  never from memory (vault `projects/rhef.md:132-136`; `instruments/`).

## Legacy names kept on purpose

These still carry JHelioviewer's naming and stay that way; each one points at something outside
this repository or at upstream.

- `org.helioviewer.jhv`: every package. Renaming would detach every upstream sync.
- The 20 `jhv.*` SAMP mtypes (`docs/samp-commands.md`): scripts and users send them.
- `jhv-notary`: the notarytool keychain profile (RELEASING.md "Legacy names").
- User agent `JHV/SWHV-` (`AppInfo.java`, `userAgent`): the data archives recognise this client.
- `libjhvmetalhost.dylib` and the `jhv/macos-arm64` resource path (`DYLIB`, `ARCH_RES` in
  `release/deploy_release.sh`).
- `.jhv` session files.
- `~/JHelioviewer-SWHV` and `~/PUNCHStudio`: legacy data folders whose `Settings` and `States` are
  copied, never moved, into `~/HFStudio` once, when it does not exist yet (`Directories.java`,
  `LEGACY_NAMES`, `migrateLegacyHome`).

## Where fork code goes

- New features: new classes in the `org.helioviewer.jhv` package for the area (`display`, `layers`,
  `io`, `movie`, `gui`, ...), so files that came from upstream carry small hunks. Before editing an
  upstream-derived file (`Actions.java`, `MenuBar.java`, `ToolBar.java`, `ImageLayer.java`,
  `State.java`, ...) run `git log upstream/master -1 -- <path>` and keep the hunk minimal.
- Checks: `extra/test/<Name>Check.java` (top level). Python tests sit beside the tool they test
  (`extra/ffmpeg/`, `extra/licenses/`) or as `extra/test/test_*.py`. Fixtures: `extra/test/data/`.
- Scripts and developer tools: `extra/tools/`. Release tooling: `release/` (no application code).
  Design notes: `docs/`.
- Retired files: `archive/`, this repository's attic, moved with `git mv`, one row in
  `archive/README.md`.
- Fork-only files (this one, `.editorconfig`) never go into an upstream pull request.

## CI

| Workflow | Runs on | Proves |
|---|---|---|
| `checks.yml` | push to master, pull requests, dispatch | `ant test` on Linux (xvfb), Windows, macOS arm64 and Intel, plus `OpenJpegDecodeCheck` against the shipped library |
| `launch.yml` | push to master, pull requests, dispatch | the zip launchers start, open a synthetic AIA FITS and draw it |
| `package.yml` | push to master, `release: published`, dispatch | Windows and Linux packages start and draw; on a release the attach job adds them |

Read results with `gh run list --repo GillySpace27/HelioFITS-Studio --branch <branch> --limit 5` and
`gh run view <id> --log-failed`. CI runs on push, and a push needs Gilly's yes.

Timing convention (7671c40d9): CI runners' clocks are too coarse to judge rates, so a wall-clock rate
assertion runs only where the `CI` environment variable is unset and prints a skip line otherwise
(`FixedRateTimerCheck`). Every other assertion runs everywhere.

## Session state (-state)

Startup precedence on record: an explicit `-state <file>` wins over the pinned default
(`startup.loadState`), which wins over the last autosave (vault `projects/jhelioviewer.md:30-35`).
A `-state` file also becomes the file the window autosaves to (`SessionStateArgCheck`). Test state
loading only with a throwaway home (above).

## Upstream

`upstream` is `https://github.com/Helioviewer-Project/JHelioviewer-SWHV.git` (add it with
`git remote add upstream <url>` if a checkout lacks it; never push to it). Master's history was
rewritten on 2026-09-16, so `git merge-base master upstream/master` is a 2020 commit: upstream fixes
are ported by hand or cherry-picked, never merged wholesale. Upstream pull requests wait on the
upstream maintainer; keep new pull-request branches independent of the open ones.

## Parallel sessions

Other Claude sessions work in this repository at the same time, in `.claude/worktrees/<name>/` and
sometimes in the main checkout (one committed 7671c40d9 while another was mapping the repository).
So: work in your own worktree; re-read a file right before editing it and patch by exact match;
never revert hunks you did not write; never `git stash`, `git reset --hard`, `git checkout -- <path>`
or `git clean` in a tree you share; never touch another session's worktree; stage only your own
paths (`git add <path>`, not `git add -A`). `extra/tools/worktree_report.sh` shows what each
worktree holds, read-only.
