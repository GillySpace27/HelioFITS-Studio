# Codex loop: HelioFITS Studio (whole repo)

**Mode:** critique (panel) · **Rounds:** 1/1 · **Ended:** panel satisfied, adjudicated
**Codex session:** 01a0927c-fbff-7e33-a817-67841a86e997 · **Panel model:** gpt-5.6-luna · **Synthesis:** gpt-5.6-sol
**Lenses:** Codex side portability, omission, architecture · Claude side provenance, dependencies, test-coverage (all sonnet)

## Verdict

Nothing unfinished in the product blocks today's cut. Every blocker worth acting on
is in the build and release plumbing, and four of the five the Codex panel called
blocking are wrong: they were produced by a reviewer that could only see `jhv-demo/`
and therefore could not see `../preview-deploy/deploy_release.sh`, which is a current,
HFStudio-aware, signing-and-notarizing pipeline. See "What I dropped".

The Windows/Linux release is further off than the repo looks. The natives all ship
already, for all four platform/arch pairs that matter, and the loading code is
platform-generic. What is missing is that `ant` cannot run at all off macOS, one
arch combination crashes at startup with a confusing message, and no build has ever
been started on either OS.

## What I verified myself

| Claim | Source | Status |
|---|---|---|
| `ant jar` builds `HFStudio.jar` clean today | provenance | confirmed (ran it) |
| `jar` depends unconditionally on `build-metal-host`, which runs `xcrun` | Codex portability | confirmed, `build.xml:131`, `:24` |
| `build.xml:26` `failonerror="false"` swallows a clang failure | Codex | confirmed |
| `Platform.buildResourceDir()` falls through to bare `/jhv/` on Windows/Linux aarch64 | dependencies | confirmed, `Platform.java:56-69` |
| `release/build.xml` builds `JHelioviewer.jar` from a class that does not exist | provenance + Codex | confirmed, `release/build.xml:7,72,78` |
| `BUILD:43,54` names `JHelioviewer.jar`; `BUILD:49` says JDK 19+ while `javac release="25"` | provenance | confirmed |
| `PaletteReleaseCheck` fails 2 of 12 assertions | test-coverage | reproduced, but **misdiagnosed**, see below |
| Kakadu is non-transferable and non-sub-licensable | dependencies | confirmed against `THIRD-PARTY.md:27-58` |
| All four platform natives (LWJGL, ANGLE, SPICE, Kakadu, ffmpeg) ship for x64 | dependencies | confirmed by `unzip -l` |
| `VERSION` is still `5.6.0-punch-preview`, same string as the published preview | mine | confirmed |

### The one finding I overturned

test-coverage called `PaletteReleaseCheck` a HIGH-severity release blocker: a
currently-broken self-check for the subsystem just reworked. I reproduced the two
failures, then read what they assert. They assert that clicking a docked palette's
toolbar button removes its section from the sidebar. `Palette.java:156` now calls
`home.revealOrFold(title)` instead, which is the behaviour you asked for on
2026-09-11 ("close/open and flash the desired controls without disappearing the
panel entirely"). The check asserts the pre-change contract. It is a stale check,
not a broken palette.

The coverage gap behind it is still real and still worth the ten minutes: the
contract changed two commits ago and nothing told anyone, because there is no way
to run the 91 checks at once.

## What I dropped from the Codex panel

Four of its five TODAY blockers rest on "there is no repository-defined HFStudio
distributable target." True of the repository, false of the project.
`../preview-deploy/deploy_release.sh` has `package`, `guide`, `publish` and
`notarize` modes; it repackages from the current `HFStudio.jar`, copies the whole
`lib/` tree and all three launchers into the cross-platform zip, requires
`RELEASE_TAG` with no default so no release is ever clobbered in place, and builds
a Developer ID signed, notarized, stapled `.app`/`.dmg` with an embedded JRE.
Specifically dropped:

- "no repository-defined HFStudio distributable target" → exists, one directory up
- "define and verify the actual shipped layout" → `repackage()` defines it
- "add versioned artifact, signing, notarization" → all present in `notarize` mode
- "provide HFStudio-specific first-run documentation" → `JHV-Preview-Guide.pdf/md`
  ships as its own release asset, and `README.txt` goes in the zip

The residue of that group that survives is small and is listed below as (1).

I also kept every kill in the panel's own `dropped` array. Its adjudicator correctly
killed the "EDR is unimplemented" line (the plan doc is stale, the code is there),
the "no Linux launcher" line, and the "Windows/Linux need a Metal host" line.

## Before you cut today

1. **Delete or clearly mark `release/`.** `release/build.xml`, `release/install4j/`
   and `jhv.fbp` all build `JHelioviewer.jar` from `org.helioviewer.jhv.JHelioviewer`,
   a class that has not existed since the fork. The only live thing in that directory
   is `heliofits-studio-launcher.sh`, which is your personal Dock launcher and
   hardcodes your home path. Someone will follow that buildfile eventually.
2. **Fix `BUILD`.** Line 43 and 54 name the wrong jar, line 49 says JDK 19 when the
   build needs 25, and the run line omits `--enable-native-access=ALL-UNNAMED`.
   Three lines.
3. **Make `build.xml:26` fail loudly.** `failonerror="false"` means a clang error
   leaves the previous `libjhvmetalhost.dylib` in place, or none, and the jar builds
   anyway. Flip it and assert the dylib exists after.
4. **Bump `VERSION`.** It still reads `5.6.0-punch-preview`, identical to the
   published preview, so the About dialog will not distinguish the two builds.
5. **Update `PaletteReleaseCheck` to the fold contract** so the next person to run
   it is not told the palettes are broken.
6. **Add an `ant test` target.** There are 91 runnable checks in `extra/test/` and
   no orchestration of any kind: no `test` target, no CI, no script, just a
   copy-paste `Run:` line in each file's own javadoc, with classpaths that differ
   file to file. Running all of them for the first time is how the stale palette
   check surfaced. Four of the 8 apparent failures are only a missing `resources`
   on the classpath.
7. **Delete `IntervalTest.java` and `IntervalsTest.java`.** They import JUnit, which
   is not in `lib/`, and test `org.helioviewer.base.interval.IntervalStore`, which
   is not in `src/`. They cannot compile and test nothing that still exists.

Not blocking, but say it in the notes: the layer and overlay lists still have their
last row sliced by the following sub-section's header (your own words in
`c0fe056b3`), presentation mode still drops out on its own after a few seconds, and
the cmd+tab symptom is unreproduced (my `LSUIElement` hypothesis was measured and
disproved, and the plist was restored).

**The one with legal teeth, which is not new but is not going away:** Kakadu's
licence (`THIRD-PARTY.md:27-58`, §10) is not transferable to a third party and may
not be sub-licensed, and `kdu_jni` is loaded unconditionally on every platform, so
it cannot simply be dropped without losing JP2/JPX/JPIP. This has been true of every
preview you have shipped. It bears equally on the Windows and Linux release. Worth a
decision rather than another quiet repeat.

## Before the Windows and Linux release

1. **`ant` cannot run off macOS at all.** `all` → `jar` → `build-metal-host` → `xcrun`,
   and Ant's `exec` defaults `failifexecutionfails` to true, so a missing `xcrun` hard
   fails the build. Guard the target on `os.family` and give the other two platforms
   their own package targets.
2. **`Platform.buildResourceDir()` has no arm64 branch for Windows or Linux.** It
   falls through to bare `/jhv/`, which exists in no natives jar, so `loadLib` throws
   `IOException: Resource /jhv/... not found` out of `AppInit.init()`. Windows on ARM
   and Linux on ARM crash at startup with a message that names a path rather than the
   problem. Either add the branch (no natives exist for those, so it would only move
   the failure) or throw the honest message. One line either way.
3. **Neither launcher checks the Java major version.** `run.sh` and `run.bat` test
   only that the selected `java` starts. On Java 24 the user gets
   `UnsupportedClassVersionError` instead of "install Java 25". Same trap you hit
   locally: `ant` builds with Homebrew JDK 26 while `run.sh` finds Temurin 25.
4. **Nothing has ever been launched on either OS.** `README.txt:26-28` says so
   outright. Source inspection cannot establish ABI compatibility. The list that
   actually needs a machine: startup, EGL/ANGLE rendering, resize and fullscreen,
   dual-display presentation mode, movie export through the extracted ffmpeg, SPICE,
   Kakadu decode, SQLite cache init, session restore.
5. **ffmpeg is extracted extensionless** (`AppInit.java:76`) and invoked as a
   subprocess. Windows will usually execute a full path without `.exe`, so this is a
   test item rather than a defect, but it is the kind of thing that only shows up on
   the machine.
6. **`Session.raiseWindow()`** (`Session.java:424-436`) raises a second instance with
   `osascript` only. Windows and Linux log and degrade. Cosmetic, but it is the
   second-instance path, which is the one a new user hits by double-clicking twice.

## Features the repo plans and has not built

All six verified against the source, not just the docs.

- **Frame-rate track**, phase 7 of the parameter animation. `docs/frame-rate-track-plan.md`
  is the handoff plan; phases 1 to 6 are built and committed, nothing in `src/`
  mentions a rate track.
- **Observer Sky as a composable secondary transform** plus the planetarium preset.
  `docs/observer-sky-secondary-scope.md:84` says not started. The primary Observer Sky
  projection is built and shipping.
- **Physically meaningful camera placement in the heliosphere**, including the silent
  Earth fallback at `UpdateViewpoint.java:64` and perspective projection.
  `docs/camera-placement-scope.md:103`.
- **16-bit PNG and 16/32-bit TIFF frame stacks**, phase 3 of `docs/output-formats-plan.md:91-95`.
  EXR and its science channel are done.
- **GPU-resident sequence filters**, `docs/sequence-gpu-scope.md`. An optimization,
  not missing function: the CPU path works.
- **Sequence-filter validation runs** that `docs/sequence-filters-plan.md:153` still
  lists as pending: the PUNCH orbital notch, filtered session save/restore, and RHEF
  over a gated movie. The implementation is marked done; the runs are not.

Stale docs that will mislead the next reader: `docs/camera-placement-scope.md:11,105`
says the rename is done and `ObserverLayer.getName` returns "Camera", but there is no
`ObserverLayer` class any more (only a `LEGACY_CAMERA_CLASS` string for session
migration) and `ViewpointLayer.getName()` returns "Viewpoint".
`docs/parameter-animation-spec.md:396` still says layer identity does not exist; it
does, and the same file's own status block says so. `docs/ddf.md` describes Java 8 and
JOGL and should be labelled historical.

## Coverage gaps worth knowing about

Of the last 25 commits, 13 touch classes that no check anywhere imports. The cluster
that matters is the one just built: `PanelLock`, `CollapsiblePane`, `SectionHost` and
`Dosido` have no check referencing them at all, which is the same subsystem whose one
existing check was asserting a contract two commits out of date. By contrast the
toolbar order, automation track and animate menu work is well covered, with
`ToolbarOrderCheck`, `AutomationTrackCheck` and `AnimateMenuCheck` added in lockstep.

One check asserts an identity rather than the app: `PlanetRateCheck` imports nothing
from `org.helioviewer` and recomputes the rate arithmetic inline, so the regression it
describes could return and it would still print PASS. Its header says why (SPICE needs
a native library it cannot load headlessly). `EclipticCheck` calls the real path and is
the one to trust.
