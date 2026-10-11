---
name: ship-hfstudio
description: Release HelioFITS Studio to its public GitHub release. Use when Gilly wants to ship, publish, cut, or refresh a HelioFITS Studio release, update the release assets or the field guide, notarize the macOS dmg, or asks what state the HelioFITS Studio release is in. Covers the build from the repository root, the tooling in release/, notarization, and the gated publish. Do NOT use for upstream PRs to Helioviewer-Project, or for feature work that is not being released.
---

# Ship HelioFITS Studio

**`release/RELEASING.md` is authoritative.** Read it when anything here is
ambiguous. If the two disagree, the procedure doc wins and this file gets fixed.

This skill lives in `release/skills/` so it travels with the repository. Claude
Code looks for project skills under `.claude/skills/`, and
`.claude/skills/ship-hfstudio` is a tracked relative symlink to this folder, so
any checkout of master finds it. A checkout of an older branch lacks the link;
make it there with:

```sh
mkdir -p .claude/skills && ln -s ../../release/skills/ship-hfstudio .claude/skills/ship-hfstudio
```

## Render the tracker after every milestone

```sh
cd ~/Documents/NWRA/PUNCH_Science/JHelioviewer-SWHV/release
python3 skills/ship-hfstudio/scripts/status.py [--done smoketest]
```

The tracker finds the repository from its own location, so it grades whichever
checkout it is run from.

Paste the output **verbatim**, starting from the title line. Never trim to the
progress bar: with several runbooks in flight a bare `[███░░░] 2/9` does not
say what is being shipped or which candidate, and being legible at a glance is
the whole point.

The tracker checks real state. It does not believe anything an earlier turn
claimed. Each row is PASS, FAIL or UNCHECKED (could not look: no network, not a
Mac); only PASS counts as done. `status.py --selftest` must exit 0: it shows every
check failing on a tag that does not exist.

## Where things live

- **The repository root** is the application. Releases ship from `master`.
- **`release/`** is the packaging tooling, the guide, the icon and this skill.
  No app code.
- **`VERSION`** at the root sets the release: tag `v<version>`, and the asset
  names in `release/assets.txt` (`{v}` is the version).

Before 0.8 these were two repos (`jhv-demo` on `demo-all`, and
`preview-deploy`). Older entries in `RELEASING.md` use those names.

## Before you start

Three things have each stopped a release night (RELEASING.md, "Things that have
actually gone wrong here", 2026-09-18 and 2026-10-11). Ask Gilly for them before
`prepare`, not after the first failure:

- **The Mac stays unlocked until notarization finishes.** `notarytool` cannot
  read the `jhv-notary` keychain profile behind a locked screen ("No Keychain
  password item found"); nothing is wrong with the credential.
- **Bitdefender's online threat prevention is paused** (or `amazonaws.com`
  excluded) while the dmg uploads. With it on, Apple's S3 upload is reset mid
  transfer ("Connection reset by peer") on every network tried.
- **Notarization passes `--no-s3-acceleration`** on every `notarytool submit`
  in `deploy_release.sh`; keep it there when editing them.

## Handing the run to Gilly's Mac

A cloud session cannot build, sign or notarize; the Mac does. Hand it the
phases, not a list of steps, so nothing the phases do gets left out (a hand-written
list naming only the Apple Silicon notarize is why 0.8.6 first shipped without
its Intel dmg):

> Ship HelioFITS Studio v<version> with the ship-hfstudio skill: run
> `release/ship.sh prepare`, then `release/ship.sh gate`, and stop there with the
> gate text and the smoke-test result. Stop on any failure.

`publish` waits for Gilly's yes in chat for that tag, as "The gate" below says.

## Order matters

`notarize` does its own clean rebuild. `package` and `publish` build **nothing**:
they package whatever jar is on disk and upload whatever dmg already exists. So:

1. commit + push the source to `master`
2. `ant clean jar build-metal-host`
3. regenerate the guide
4. `notarize` (produces the dmg), then `MAC_ARCH=x64 ... notarize` for the
   Intel dmg (needs the Intel JDK at `release/.jdk-x64`; RELEASING.md step 4)
5. smoke-test the app **inside the dmg** (and the Intel dmg under Rosetta when
   one was built), then read its log as "The smoke test" below says
6. **gate**, then `publish`
7. wait for CI's `package` run on the tag, which attaches the Windows and
   Linux packages, then confirm asset dates changed (eight assets, seven without an Intel dmg)

Skipping step 4 before step 6 is how a month-old dmg got shipped on 2026-07-14
with nothing failing and nothing warning.

## Phases

`release/ship.sh` runs RELEASING.md in phases that stop at the first red tracker
row: `bump <version>`, `prepare`, `gate`, `publish`, `confirm` (RELEASING.md, "The
phases"). `gate` ends with the text to paste to Gilly. `publish` needs the tag typed
at a terminal or, with none, `HFS_PUBLISH_APPROVED=<tag>` for that one invocation:
set it only after his yes in chat, only to that tag, never for another.

A notarization that stops with a submission pending is finished with
`./deploy_release.sh notarize-resume`, never by rebuilding.

### Driving it without a terminal (an agent)

Run the phases; never hand-pick steps from RELEASING.md. On 2026-10-10 a hand-run
subset shipped 0.8.6 without its Intel dmg, which `prepare` would have built.
Each outward step has its own variable, set for one invocation, to one tag, only
after Gilly's yes in chat for that step; one never answers another:

```sh
HFS_NOTARIZE_APPROVED=v<version> release/ship.sh prepare   # uploads both dmgs to Apple
HFS_SMOKE_APPROVED=v<version>    release/ship.sh gate      # smoke_dmg.sh stands in for the typed smoke test
HFS_PUBLISH_APPROVED=v<version>  release/ship.sh publish   # after his yes to the gate text
release/ship.sh confirm
```

`HFS_SMOKE_APPROVED` means Gilly accepts the automated launch check
(`release/smoke_dmg.sh`: starts, draws, no uncaught exception or missing class)
in place of loading layers and exporting a movie by hand. Ask for it as its own
question; when he says no, the gate waits for him at a terminal.

When `notarize` fails at the upload: "screen locked?" means unlock the Mac; a
`Connection reset by peer` from S3 has been Bitdefender's network extension
(2026-10-10), which only Gilly can pause. Rerun `prepare`; a rerun is safe.

## Releases are immutable

Every release gets its **own tag**, `v<version>` from the `VERSION` file, cut at
the commit it was built from, and its own release object. Assets are never
replaced in place, so the previous build stays downloadable for anyone it was
working for. To ship again, bump `VERSION` first:

```sh
release/ship.sh publish         # asks for the tag, then tags and publishes v<contents of VERSION>
```

`publish` refuses a tag that already has a release. A release is never deleted:
to correct the newest one, withdraw it as RELEASING.md "What ships" says (Gilly's
yes, superseded note, fix, bump, ship). The short link points at the
`/releases` index rather than any tag, so it never needs updating and always
offers the older builds underneath the newest.

## The gate

`publish` is public. **Ask Gilly in chat, every release.** A yes for one never
carries to the next.

Get the gate text from `./deploy_release.sh publish --dry-run` (in `release/`)
and paste its output verbatim; it names the tag, the commit, the release that
stays as the way back, and every asset with its sha256. A `!!` line is a
refusal: fix what it names (RELEASING.md step 6 lists them) before asking.

State plainly what changes, naming both the new tag and the commit: "this
publishes a new public release `<tag>` from commit `<sha>`, which becomes what
gilly.space/jhv offers first." Say what the previous release was, so it is
clear it stays in place as the way back. The link has been sent to Sarah Gibson,
Ian Hewins, Yara De Leo and Curt de Koning, so this reaches real collaborators.

Nothing else in the procedure needs confirmation. Preparation steps that only
touch local files should just run.

## The smoke test

RELEASING.md step 5 and the five items `ship.sh gate` prints are the test. Two
additions, both from 0.8.6:

- **Run it with a throwaway home**, so nothing touches Gilly's `~/HFStudio`:
  start the executable inside the mounted bundle (`Contents/MacOS/`) with
  `JAVA_TOOL_OPTIONS="-Duser.home=$(mktemp -d)"`.
- **Then read the log**, under that home's `HFStudio/Logs/`, and fail the test
  on any uncaught exception, `NoClassDefFoundError`, `ClassNotFoundException` or
  `SEVERE` line, even when the window looked fine. The first 0.8.6 dmg drew its
  images and then died on `NoClassDefFoundError: java/net/http/HttpClient`: the
  development build runs on the full JDK, and only the trimmed runtime inside the
  dmg lacked the module. `extra/test/test_runtime_modules.py` now guards that
  case in CI; the log read catches the next one it does not know about.

## Session-only milestone

`smoketest` cannot be checked from outside: launching the mounted app and
looking at it is a judgement. Pass `--done smoketest` **only** after it has
actually been done, and never bake it into the Orrery registry args, which
would make the dashboard assert on every unattended refresh that a human
looked at something.

## After publish

`release/ship.sh confirm` waits for CI's packages and grades every asset. Two
rows need a hand after it:

- **`assets_all`:** eight assets with an Intel dmg. Compare each sha256 GitHub
  lists with the one in the gate text.
- **`page_fallback`:** the gilly.space download page finds the newest release by
  itself, but its fixed fallback links (used when GitHub cannot be reached) name
  a version. Update them in `heliofits-studio/index.html` of
  GillySpace27/GillySpace27.github.io on a branch and PR; merging it publishes the
  site and needs Gilly's yes.

## Hand-off boundary

The runbook ends when the release carries the assets just built. It does
**not** notify anyone (that is a separate email), does not touch the upstream
PRs, and does not build Linux or Windows itself: CI does, on the published tag,
and attaches them only if they pass its launch checks. Neither has been used on
real hardware yet.

## Credentials

- Apple **Developer ID Application** cert in the login keychain, team
  `UB45PPC2JS`.
- `notarytool` keychain profile **`jhv-notary`** (app-specific password). The
  name is a legacy of the preview; see "Legacy names" in `RELEASING.md` before
  changing it.
- `JAVA_HOME` must be **Temurin 25** for `notarize`, not Homebrew `openjdk@25`.

No key material lives in this repo or this file.

## When something goes wrong

Fix it, then append it to the **"Things that have actually gone wrong here"**
section of `RELEASING.md` in the same session, with the date, while the detail
is fresh. Never delete an entry from that list.
