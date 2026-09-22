# release/

The build and release tooling for PUNCHStudio: the signed and notarized
macOS `.dmg`, the cross-platform `.zip`, and the field guide. The application
source is the repository root; nothing in this folder is application code.

**The full procedure, including the notarize-before-publish ordering and the
incident log, is in [RELEASING.md](RELEASING.md).** Read it before shipping.

## What is here

- `deploy_release.sh`: the release pipeline, with the four modes below.
- `RELEASING.md`: the authoritative release procedure and the log of what has
  gone wrong.
- `build_guide.py`, `guide_content.json`, `guide_assets/`: the field guide.
  The script renders the prose in `guide_content.json` and the figures in
  `guide_assets/` into `PUNCHStudio-Guide.pdf` and `PUNCHStudio-Guide.md`.
- `make_squircle_icon.py`, `PUNCHStudio_icon_squircle.icns`,
  `PUNCHStudio_icon_squircle_1024.png`: the macOS app icon and the script that
  composes it.
- `fabric_suvi.json.gz`: the demo point cloud attached to every release.
- `skills/ship-punchstudio/`: the release tracker (`scripts/status.py`) and the
  assistant runbook that drives it.
- `punchstudio-dev-launcher.sh`: a development convenience, not part of the
  release. It is the source of the script inside
  `/Applications/PUNCHStudio Dev.app/Contents/MacOS/punchstudio-dev`, a Dock
  tile that rebuilds the main checkout and runs it. It hardcodes one source path
  and one Homebrew JDK, so it works on one machine on purpose. The release
  app is `PUNCHStudio.app`, a different name, so the two sit side by side.

## Pipeline

```
./deploy_release.sh package    # regenerate the guide + repackage the zip locally (no network)
./deploy_release.sh guide      # re-upload only the guide PDF+MD to the GitHub release
./deploy_release.sh publish    # repackage + create the public release (zip + dmg + guide + demo cloud)
./deploy_release.sh notarize   # build a signed + notarized + stapled macOS .app inside a .dmg
```

The version is read from `../VERSION`. Assets are named `PUNCHStudio-<version>.dmg`
and `PUNCHStudio-<version>.zip`, and the release tag is `v<version>`. The GitHub
repository is set once, as `REPO` near the top of `deploy_release.sh`; the
tracker and the guide read it from there.

## Prerequisites

- **Java 25** for the build (`brew install openjdk@25`).
- `reportlab` for `build_guide.py`; Pillow and numpy for `make_squircle_icon.py`.
- For `notarize`: a full **Temurin 25** JDK (jpackage embeds it as the app's
  runtime, and Homebrew's `openjdk@25` ships without the needed jmods), an Apple
  **Developer ID Application** certificate, and a `notarytool` keychain profile
  named `jhv-notary`. Run it as `JAVA_HOME=<temurin-25> ./deploy_release.sh notarize`.

## Release

Releases: <https://github.com/GillySpace27/PUNCHStudio/releases>.
Shareable short link: <https://gilly.space/punchstudio>, which redirects to that index.

## History

Until the 0.8 pre-release this tooling lived in a separate repository,
`preview-deploy`, which built from a sibling `jhv-demo` worktree on the
`demo-all` branch. It moved here so the tooling and the source it packages are
versioned together. Older entries in `RELEASING.md` use the old names.

`build.xml` and `install4j/` used to live in this folder. They were upstream
JHelioviewer's packaging, they built a `JHelioviewer.jar` from a
`org.helioviewer.jhv.JHelioviewer` class that has not existed since the fork,
and nothing invoked them. Removed 2026-09-12; `git log` has them if a future
Windows or Linux installer wants the install4j media definitions back as a
starting point.
