---
name: ship-punchstudio
description: Release PUNCHStudio to its public GitHub release. Use when Gilly wants to ship, publish, cut, or refresh a PUNCHStudio release, update the release assets or the field guide, notarize the macOS dmg, or asks what state the PUNCHStudio release is in. Covers the build from the repository root, the tooling in release/, notarization, and the gated publish. Do NOT use for upstream PRs to Helioviewer-Project, or for feature work that is not being released.
---

# Ship PUNCHStudio

**`release/RELEASING.md` is authoritative.** Read it when anything here is
ambiguous. If the two disagree, the procedure doc wins and this file gets fixed.

This skill lives in `release/skills/` so it travels with the repository. Claude
Code looks for project skills under `.claude/skills/`, so to use it from a
checkout, link it there:

```sh
mkdir -p .claude/skills && ln -s ../../release/skills/ship-punchstudio .claude/skills/ship-punchstudio
```

## Render the tracker after every milestone

```sh
cd ~/Documents/NWRA/PUNCH_Science/JHelioviewer-SWHV/release
python3 skills/ship-punchstudio/scripts/status.py [--done smoketest]
```

The tracker finds the repository from its own location, so it grades whichever
checkout it is run from.

Paste the output **verbatim**, starting from the title line. Never trim to the
progress bar: with several runbooks in flight a bare `[███░░░] 2/9` does not
say what is being shipped or which candidate, and being legible at a glance is
the whole point.

The tracker checks real state. It does not believe anything an earlier turn
claimed.

## Where things live

- **The repository root** is the application. Releases ship from `master`.
- **`release/`** is the packaging tooling, the guide, the icon and this skill.
  No app code.
- **`VERSION`** at the root sets the release: tag `v<version>`, assets
  `PUNCHStudio-<version>.dmg` and `PUNCHStudio-<version>.zip`.

Before 0.8 these were two repos (`jhv-demo` on `demo-all`, and
`preview-deploy`). Older entries in `RELEASING.md` use those names.

## Order matters

`notarize` does its own clean rebuild. `package` and `publish` build **nothing**:
they package whatever jar is on disk and upload whatever dmg already exists. So:

1. commit + push the source to `master`
2. `ant clean jar build-metal-host`
3. regenerate the guide
4. `notarize` (produces the dmg), then `MAC_ARCH=x64 ... notarize` for the
   Intel dmg (needs the Intel JDK at `release/.jdk-x64`; RELEASING.md step 4)
5. smoke-test the app **inside the dmg**
6. **gate**, then `publish`
7. wait for CI's `package` run on the tag, which attaches the Windows and
   Linux packages, then confirm asset dates changed (eight assets, seven without an Intel dmg)

Skipping step 4 before step 6 is how a month-old dmg got shipped on 2026-07-14
with nothing failing and nothing warning.

## Releases are immutable

Every release gets its **own tag**, `v<version>` from the `VERSION` file, cut at
the commit it was built from, and its own release object. Assets are never
replaced in place, so the previous build stays downloadable for anyone it was
working for. To ship again, bump `VERSION` first:

```sh
./deploy_release.sh publish     # tags and publishes v<contents of ../VERSION>
```

`publish` refuses a tag that already has a release. The short link points at the
`/releases` index rather than any tag, so it never needs updating and always
offers the older builds underneath the newest.

## The gate

`publish` is public. **Ask Gilly in chat, every release.** A yes for one never
carries to the next.

State plainly what changes, naming both the new tag and the commit: "this
publishes a new public release `<tag>` from commit `<sha>`, which becomes what
gilly.space/jhv offers first." Say what the previous release was, so it is
clear it stays in place as the way back. The link has been sent to Sarah Gibson,
Ian Hewins, Yara De Leo and Curt de Koning, so this reaches real collaborators.

Nothing else in the procedure needs confirmation. Preparation steps that only
touch local files should just run.

## Session-only milestone

`smoketest` cannot be checked from outside: launching the mounted app and
looking at it is a judgement. Pass `--done smoketest` **only** after it has
actually been done, and never bake it into the Orrery registry args, which
would make the dashboard assert on every unattended refresh that a human
looked at something.

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
