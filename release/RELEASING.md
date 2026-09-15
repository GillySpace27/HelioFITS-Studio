# Releasing HelioFITS Studio

Authoritative procedure for shipping a HelioFITS Studio release. If this file
and `skills/ship-hfstudio/SKILL.md` ever disagree, **this file wins** and the
skill gets fixed.

Gilly can follow this by hand with no assistant present. That is the point.

## What ships, and from where

One repository, **`GillySpace27/JHelioviewer-SWHV`**:

| | Path | Holds |
|---|---|---|
| **Source** | the repository root (`PUNCH_Science/JHelioviewer-SWHV`), branch `master` | The application. Every shipped feature. |
| **Tooling** | `release/` in the same repository | `deploy_release.sh`, the guide generator, the icon, the tracker. No application code. |

The repository slug is set once, as `REPO` near the top of `deploy_release.sh`;
the tracker and the guide generator read it from there. The app itself carries
the same slug in the update-check, download and issue-tracker URLs in
`src/org/helioviewer/jhv/app/AppInfo.java`, so a rename touches both.

The version is the **`VERSION`** file at the repository root. It sets the tag
(`v<version>`) and the asset names. `jpackage` needs a purely numeric version,
so `VERSION` must look like `1.0.0`; `deploy_release.sh` refuses anything else.

**Every release gets its own tag and its own release object.** Assets are never
replaced in place. The previous release keeps its binaries, so a collaborator
whose workflow breaks on a new build can go back to the one that worked. The tag
is cut at the commit the build actually came from, which is also the only
reliable way to answer "what source is in this binary?".

Tags follow `VERSION`: `v1.0.0`, then whatever the next bump is. `publish`
**refuses** to touch a tag that already has a release, so shipping again means
bumping `VERSION` first. To correct a mistake on the newest release, delete that
release deliberately by hand first.

Public link: **<https://gilly.space/jhv>**, which redirects to the repo's
**`/releases` index**, not to any single tag. That page always shows the newest
release with its assets and lists the older ones underneath, so it cannot go
stale when a new release is cut, and it doubles as the way back to a previous
build. GitHub Pages is case-sensitive, so `/jhv` and `/JHV` are separate paths;
both exist and both were fixed. Only ever hand out the lowercase form.

Five assets:

- `HFStudio-<version>.dmg`: signed, notarized, stapled macOS app with an
  embedded JRE. **Apple Silicon only.** Double-click, no Java, no Gatekeeper
  prompt. This is what almost everyone should get.
- `HFStudio-<version>.zip`: cross-platform, needs the user to install Java 25.
  Carries Linux and Windows launchers that **have never been run**.
- `HFStudio-Guide.pdf` / `.md`: the field guide, generated from
  `guide_content.json` + `guide_assets/`.
- `fabric_suvi.json.gz`: demo point cloud, opened from the Point Cloud layer.

**macOS arm64 is the only tested platform.** Say so when sharing.

## Legacy names

A few identifiers still carry the preview's JHelioviewer naming. Each one points
at something outside `release/`, so renaming it is a deliberate change with a
second half, not a string edit.

- **`jhv-notary`**, the `notarytool` keychain profile (the `NOTARY_PROFILE`
  default in `deploy_release.sh`). It is a credential in Gilly's login keychain.
  Store the credential under the new name first (the command is in the
  2026-08-23 credentials entry below), then change the default. Changing only
  the script breaks `notarize`.
- **`gilly.space/jhv`** (and its `/JHV` duplicate), the short link handed to
  collaborators. It is a redirect hosted on gilly.space, outside this
  repository, and the tracker's `live` check follows it. A new short link needs
  its redirect created there first, and the old one kept working, since it has
  been sent to people.
- **`GillySpace27/JHelioviewer-SWHV`**, the repository slug. Kept for 1.0; see
  above for the two places it lives.
- **`org.helioviewer.jhv`, `libjhvmetalhost.dylib`, the `jhv/macos-arm64`
  resource path**: application identifiers that `deploy_release.sh` has to
  match (`--main-class`, `DYLIB`, `ARCH_RES`). They change with the app code or
  not at all.

## The four modes

```
./deploy_release.sh package     # regenerate guide + repackage zip, locally. No network.
./deploy_release.sh guide       # re-upload ONLY the guide PDF+MD. Fast iterate.
./deploy_release.sh publish     # repackage + tag + create the public release. OUTWARD.
./deploy_release.sh notarize    # clean rebuild -> signed + notarized + stapled .dmg
```

The asymmetry that bites people:

- **`notarize` does a clean rebuild of its own** (`ant clean jar
  build-metal-host` at the repository root), so its `.dmg` is always built from
  the current working tree.
- **`package` and `publish` do NOT build anything.** They repackage whatever
  `HFStudio.jar` is sitting at the repository root, and `publish` uploads
  whatever `.dmg` already exists in `release/`. A stale jar or a stale dmg ships
  silently.

Hence the ordering rule below.

## Procedure

### 1. Land and push the source

The jar is built from the **working tree**, not from `HEAD`. Uncommitted source
in `src` or `resources` therefore ends up inside the shipped binary while the
recorded revision points at a commit that does not contain it, which makes
"what is actually in this build?" unanswerable later. The same goes for a
`VERSION` bump: commit it with the source, or the tag and the file in the tagged
commit disagree.

```sh
cd ~/Documents/NWRA/PUNCH_Science/JHelioviewer-SWHV
git branch --show-current                 # must be master
git status --short src resources VERSION  # must be empty
git push origin master
```

### 2. Build the jar, with the dylib

```sh
cd ~/Documents/NWRA/PUNCH_Science/JHelioviewer-SWHV
ant clean jar build-metal-host
```

`build-metal-host` compiles `native/macos/jhv_metal_host.m` into
`lib/natives-macos/libjhvmetalhost.dylib`, and `ant clean` deletes it. A jar
built without it launches and then dies with `NoClassDefFoundError:
MacAngleBridge` (2026-08-18, below). In this repository's `build.xml`, `jar`
depends on `build-metal-host`, so `ant clean jar` produces the dylib as well;
naming the target explicitly costs nothing.

Confirm the jar records the commit you just pushed:

```sh
unzip -p HFStudio.jar META-INF/MANIFEST.MF | grep -i revision
git rev-list --count HEAD          # must match
```

That `revision` is `git rev-list --count HEAD` evaluated at build time. It is
the only provenance link between a shipped binary and its source, so it is
worth one command to check.

### 3. Regenerate the guide

```sh
cd ~/Documents/NWRA/PUNCH_Science/JHelioviewer-SWHV/release
python3 build_guide.py
```

Screenshots in `guide_assets/` are captured by hand from a running build. If a
feature changed how something looks, recapture before regenerating, or the
guide documents a version that no longer exists. A figure named in
`guide_content.json` with no file in `guide_assets/` is skipped without a
warning; as of the move into this repository, six of them are (`fig_punch_dialog`,
`fig_aspiics`, `fig_rhef`, `fig_grid`, `fig_trackcme`, `fig_pointcloud`).

`build_guide.py` supports `**bold**` and `` `mono` `` only. `*italic*` renders
literally. `@VERSION@` and `@REPO@` in `guide_content.json` are replaced with
the `VERSION` file and `REPO` from `deploy_release.sh`, so the guide never names
a stale version or repository.

### 4. Notarize (builds the dmg)

```sh
cd ~/Documents/NWRA/PUNCH_Science/JHelioviewer-SWHV/release
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home \
  ./deploy_release.sh notarize
```

Takes several minutes and talks to Apple twice (timestamping, then the notary
service). Both are network steps that fail transiently; see the incident log.

Verify before going further:

```sh
xcrun stapler validate "HFStudio-$(cat ../VERSION).dmg"
spctl -a -t open --context context:primary-signature -v "HFStudio-$(cat ../VERSION).dmg"
# want: accepted / source=Notarized Developer ID
```

### 5. Smoke-test the actual artifact

Mount the dmg and launch the app it contains, not the jar you built. This is
the step that catches a bundle that is signed correctly and still broken.

Run it from the mounted image. The bundle is named `HelioFITS Studio.app`, the
same as the development launcher tile in `/Applications`
(`heliofits-studio-launcher.sh`), so dragging it into `/Applications` on the
development Mac replaces that tile.

Quit any running HelioFITS Studio first. A second instance cannot take the JPIP
ehcache persistence lock, and the failure is not contained: `levelCache` stays
null and every image read throws, which presents as a rendering bug rather than
an "already running" message.

### 6. Publish (GATE)

**Stop. Ask Gilly, in chat, every time.** This makes a public artifact live at
a link that has been sent to colleagues (Sarah Gibson, Ian Hewins, Yara De Leo,
Curt de Koning). A yes for one release never carries to the next.

State plainly what is about to happen, for example: "this will create the public
`v1.0.0` release, with the `.dmg`, `.zip` and guide built from commit `<sha>`."

```sh
cd ~/Documents/NWRA/PUNCH_Science/JHelioviewer-SWHV/release
./deploy_release.sh publish
```

This tags the current commit as `v<version>`, pushes the tag, and creates a
**new** release. It will refuse outright if that tag already has a release.
There is no tag override: the tag comes from `VERSION` alone.

The short link needs no update: it points at `/releases`, which always shows the
newest.

### 7. Confirm what actually landed

```sh
gh release view "v$(cat ../VERSION)" --repo GillySpace27/JHelioviewer-SWHV \
  --json assets --jq '.assets[] | "\(.name)  \(.size)  \(.updatedAt)"'
```

Every asset's `updatedAt` should be from this run. An asset with an older date
was not replaced, which is the exact failure logged for 2026-07-14 below.

## Hand-off boundary

This procedure ends at "the assets on the release are the ones just built".
It does **not** cover:

- **Telling anyone.** The release going live sends no notification. Emailing
  collaborators is a separate, human step.
- **Upstream contribution.** Shipping a release is unrelated to the PRs against
  `Helioviewer-Project/JHelioviewer-SWHV`, which are gated on bogdanni.
- **Non-macOS platforms.** The Linux and Windows launchers ship untested. The
  first real Windows user will be an external collaborator.

## Things that have actually gone wrong here

Append, with a date, whenever something bites. Never delete an entry.

Entries dated before 2026-09-15 describe the preview layout and are kept as
written: the source in a `jhv-demo` worktree on the `demo-all` branch, the
tooling in a separate `preview-deploy` repository
(`GillySpace27/jhelioviewer-preview-builder`, private), assets named
`JHelioviewer-PUNCH-preview.dmg` / `.zip` and `JHV-Preview-Guide.pdf`, and
hand-picked pre-release tags. The public preview was first tagged
`v5.6.0-punch-preview`, then retagged to `v5.6a-coronal-research` on the same
release object; the next preview would have been `v5.6b-coronal-research`.

- **2026-07-14: `publish` silently shipped a month-old dmg.** After the tooling
  moved into `preview-deploy/`, `$DMG` pointed at a path with no local dmg, so
  `publish` uploaded the zip and guide and left the *previous* dmg sitting on
  the release. Nothing failed and nothing warned. **Always run `notarize`
  before `publish`**, and check asset dates afterwards (step 7).

- **2026-07-22: `notarize` died with `NoSuchFileException .../runtime/Contents/Home/lib`.**
  `JAVA_HOME` was `/opt/homebrew/opt/openjdk@25`, a Homebrew prefix with no
  `lib/modules`, which is not a valid `jpackage --runtime-image` root. `ant`
  does not care; `jpackage` does. Use the Temurin path in step 4.

- **2026-07-22: Apple's timestamp server throttled a signing burst.**
  `codesign --timestamp` failed with "A timestamp was expected but was not
  found". Not a code problem. The retry in the script was widened to 12 x 30s
  to outlast it.

- **2026-07-22: `xcrun stapler staple` hung on CloudKit.** macOS has no
  `timeout`, so the script guards it with a perl `alarm`. If it hangs anyway,
  it is Apple's side.

- **2026-07-22: an interrupted notarize looked like a failure but had succeeded.**
  A submission that reaches `status: Accepted` is done server-side even if the
  local `--wait` or staple is interrupted. Query
  `xcrun notarytool info <id> --keychain-profile jhv-notary`, then staple the
  dmg you already have. Do not rebuild.

- **2026-08-18: `ant jar` alone produced a jar that dies at runtime.** Missing
  `libjhvmetalhost.dylib`, deleted by a prior `ant clean`. At the time `jar`
  did not depend on `build-metal-host`, and only `ant run` pulled it in; `ant
  run` cannot forward command-line arguments, so it cannot load a state file.
  See step 2.

- **2026-08-20: the release appeared to have vanished.** `gh release view
  v5.6.0-punch-preview` returned "not found" because the release had been
  retagged to `v5.6a-coronal-research`. Compounded by a transient NWRA
  webfilter failure at the same moment, which made it look like a network
  problem. Use the current tag.

- **2026-08-22: a session save taken mid-load could erase a layer, and the
  session restore had never run at all.** Not a release bug, but it is why the
  build being shipped now differs from the one on the release. Noted because
  "the dmg on the release is a month older than the source" was true for weeks
  without anything surfacing it. That gap is what the tracker exists to show.

- **2026-08-24: the immutability fix had the same heredoc-swallowing bug
  twice more, and one new bash-syntax trap.** `_asset_current`'s heredoc
  terminator lost its trailing newline to `.strip()` when three of it were
  joined into `_PUBLISHED`, so `EOF)` landed on one line again -- the exact
  class of bug logged the same night for `_DMG_NOTARIZED`, just in a second
  place `.strip()` reached. Separately, the `live` check wrapped `_PUBLISHED`
  (which already opens with `(`) in another `f"({_PUBLISHED})"`, producing a
  leading `((` -- bash reads that as `((...))` arithmetic evaluation, not two
  nested subshells, and fails with a syntax error before any real check runs.
  Both looked identical to a missing/stale asset: an unconditional red X in
  0.0s. Caught by running each composed check standalone with `bash script.sh;
  echo $?` instead of trusting the tracker's own verdict, then negative-tested
  against a nonexistent tag and the retired `v5.6a` tag to confirm the fixed
  checks still fail where they should. Lesson repeated: any check assembled by
  string concatenation of heredocs needs its own smoke test, not just a green
  run against the one case that was in front of me when I wrote it.

- **2026-08-23: `stapler validate` is not a usable check.** Measured on one
  dmg in one session: **0.3s** cached, **30s** warm, and **60s then exit 68**
  cold. It downloads from Apple's CloudKit, so it intermittently reports a
  correctly stapled dmg as bad. The tracker briefly used it and flickered red on
  a dmg that was fine. `notarize` now writes **`.notarize-run.json`** pinning the
  dmg by sha256 after the staple and the validate have both passed under
  `set -e`, and the tracker checks that receipt plus a local `spctl` instead.
  Deterministic, 0.3s. A check that intermittently cries wolf is one you learn
  to ignore, which is worse than not having it.

- **2026-08-23: the shareable link had been sending people to a page with
  nothing on it.** `gilly.space/jhv` (and the `/JHV` duplicate) redirected to
  `releases/tag/v5.6.0-punch-preview`, retired when the release was retagged.
  That URL still returns **200**, because GitHub renders a page for any tag that
  exists, so every naive check passed. It is a bare tag page: no release, no
  assets. Anyone following the link Gilly had sent to collaborators found
  nothing to download, and had done for weeks. Fixed by pointing both copies at
  the **`/releases` index** rather than a tag, so it cannot go stale again. The
  tracker's `live` check now follows the redirect and asserts the destination
  actually offers the dmg; the old version only checked for a 200 and was
  therefore incapable of failing.

- **2026-08-23: the release tag did not describe the release.**
  `v5.6a-coronal-research` pointed at `4d321978a`, a commit by Bogdan Nicula on
  `origin/master` that is **not an ancestor of `demo-all`** at all. Anyone
  checking out "the source for this release" got upstream code containing none
  of the fork's features. No tag had ever marked a shipped `demo-all` build.
  This is why releases are now tagged at the build commit, and why `publish`
  cuts and pushes that tag itself rather than trusting one to already exist.

- **2026-08-23: the `jhv-notary` credentials stopped working (HTTP 401).**
  `xcrun notarytool history --keychain-profile jhv-notary` returned "Invalid
  credentials. Username or password is incorrect." The keychain item still
  exists; the app-specific password behind it is no longer valid. Apple expires
  or invalidates these (a password revoked at appleid.apple.com, an Apple ID
  password change, or a very old token). Nothing in the build is wrong and
  rebuilding does not help. Fix, and it needs Gilly's Apple ID:

  1. Generate a new app-specific password at <https://account.apple.com>,
     Sign-In and Security, App-Specific Passwords.
  2. `xcrun notarytool store-credentials jhv-notary --apple-id "gilly@nwra.com" --team-id UB45PPC2JS --password "<new-app-specific-password>"`
  3. `xcrun notarytool history --keychain-profile jhv-notary` should list
     submissions rather than 401.

  Until then `notarize` cannot run, and **`publish` must not be run either**:
  it would upload the fresh zip and guide next to the *July* dmg, which is the
  2026-07-14 failure repeated deliberately.

- **2026-08-23: the jar on disk recorded revision 13211 while `HEAD` was 13215.**
  Found while building this runbook. The jar had been built from the working
  tree before those four commits were made, so it carried the *previous*
  commit count. Functionally the same code, but a build whose recorded
  provenance points at the wrong commit cannot be reasoned about later. Rebuild
  after committing, and check the manifest (step 2).

- **2026-09-15: the tooling moved into the application repository for 1.0.**
  `preview-deploy` assumed the source at `../jhv-demo`, and that worktree was
  being removed. The tooling now lives in `release/`, builds from the repository
  root, reads the version from `VERSION`, tags `v<version>`, names the assets
  `HFStudio-<version>.*` and the guide `HFStudio-Guide.*`, and publishes a
  normal release rather than a pre-release. `RELEASE_TAG` is gone because
  `VERSION` now supplies the tag; `publish` still refuses an existing release.
  At the same time the root `README.txt` that `repackage` copied into the zip
  had moved to `archive/preview/` (commit `65b4bbbf9`), which would have aborted
  `package` under `set -e`. `repackage` now copies a root `README.txt` or
  `README.md` if one exists and warns loudly if neither does.
