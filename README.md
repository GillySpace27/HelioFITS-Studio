# PUNCHStudio

[![checks](https://github.com/GillySpace27/PUNCHStudio/actions/workflows/checks.yml/badge.svg?branch=master)](https://github.com/GillySpace27/PUNCHStudio/actions/workflows/checks.yml)

PUNCHStudio is a desktop viewer for solar and heliospheric imagery, built around coronagraph and wide-field data such as NASA's PUNCH mission, SOHO/LASCO and PROBA-3/ASPIICS. It is a fork of [JHelioviewer](https://www.jhelioviewer.org), the open-source solar image browser of the ESA/NASA Helioviewer Project. We kept JHelioviewer's 3-D view of the Sun, its timelines and its event overlays, and added the tools we needed to work with the outer corona.

**Status: pre-release.** The 0.8 releases are published so that they can be tried, and broken, ahead of 1.0. We use it every day on Apple Silicon Macs. Since 0.8.2 there are also packages for Intel Macs, Windows and Linux; our automated checks build and start each of them on every release, but nobody has yet used the application itself on those systems. Please tell us what goes wrong (see [Reporting problems](#reporting-problems)).

## Why this fork exists

We work with NASA's PUNCH mission and the wider coronagraph record, and that work needed several things JHelioviewer did not do: load PUNCH data, read calibrated LASCO FITS straight from NRL, stretch the outer corona so that it has room to read, and equalize its steep radial falloff. PUNCHStudio is where we build those tools and share them with other researchers.

We distribute it as a separate application under its own name so that it is not mistaken for an official JHelioviewer release, and so that problems with our additions come to us rather than to the JHelioviewer team, who did not write that code. Earlier builds were published on this repository as the *JHelioviewer PUNCH & Coronal Research Distribution* (tagged v5.6a to v5.6d); PUNCHStudio continues that line. It was called HelioFITS Studio, and then HFStudio, during September 2026: the first name was too easily confused with the [HelioFITS](https://gilly.space/heliofits/) preview plugin, which is a separate project, and the second was hard to say and told nobody what the application is for. The 0.8.1 and 0.8.2 downloads carry the HFStudio name.

## Relationship to JHelioviewer

JHelioviewer is developed by the ESA JHelioviewer team as part of the ESA/NASA Helioviewer Project, and was enhanced at ROB/SIDC. PUNCHStudio is not affiliated with or endorsed by that project. We do intend to stay close to it, in both directions: we merge JHelioviewer's own development into this fork (version 0.8.0 includes it up to 28 July 2026), and we offer back what is of general use.

Several of our additions have already been taken into JHelioviewer's development line and are credited in its pending 5.10.0 changelog: the PUNCH layer and its colormap, the RHEF filter, the two wide-field projections (called RadialWarp and RectWarp there, Helioradial and Helioradial Unrolled here) and the grid colour controls. Smaller fixes have followed, and further changes are open as [pull requests](https://github.com/Helioviewer-Project/JHelioviewer-SWHV/pulls?q=is%3Apr+author%3AGillySpace27).

## What is different from JHelioviewer

- PUNCH data straight from the SDAC archive, named by product and pipeline version, with a shared display range so that a movie does not strobe from frame to frame.
- Native LASCO C2 and C3 FITS from NRL, with monthly background subtraction, the correct orientation on either side of SOHO's roll flips, and pointing recovered for frames whose headers lost it.
- The Sun-centred Helioradial projection and its unrolled form, where a single control trades linear distance for compression of the outer corona, the RHEF radial histogram equalizing filter, and filters that work across a sequence of frames.
- CME tracking against the CACTus catalog, a point cloud layer, and an experimental Observer Sky projection that looks outward from the observer rather than at the Sun.
- An HDR canvas on Macs with an EDR display, and deep-colour movie, PNG and EXR export.

The full record, including the JHelioviewer changes merged here, is in [changelog.md](changelog.md).

## Installing

Every package on the [Releases page](https://github.com/GillySpace27/PUNCHStudio/releases) carries its own Java runtime, so there is nothing else to install. The 0.8.2 downloads are named HFStudio, the name this application had until 0.8.3.

- **Apple Silicon Mac:** open `PUNCHStudio-<version>.dmg` and drag PUNCHStudio to Applications. The dmg is signed and notarized.
- **Intel Mac:** the same, with `PUNCHStudio-<version>-intel.dmg`, also signed and notarized.
- **Windows:** unzip `PUNCHStudio-<version>-windows.zip` anywhere and run `PUNCHStudio\PUNCHStudio.exe`. The app is not code-signed yet, so Windows may warn that it comes from an unknown publisher; choose *More info*, then *Run anyway*.
- **Linux:** unpack `PUNCHStudio-<version>-linux.tar.gz` anywhere and run `PUNCHStudio/bin/PUNCHStudio`.

The Intel Mac, Windows and Linux packages pass our automated checks but have not yet been used on real machines, so we would especially like to hear how they go.

For anything else, the cross-platform `PUNCHStudio-<version>.zip` runs wherever Java 25 or newer is installed (for example Temurin 25 from [adoptium.net](https://adoptium.net), or `brew install openjdk@25`): start `run.command` on macOS, `run.sh` on Linux or `run.bat` on Windows. On Windows the zip's JPEG 2000 decoder also needs Microsoft's Visual C++ runtime, which most machines already have.

## Coming from JHelioviewer

The first time it runs, PUNCHStudio copies your settings and saved states from `~/JHelioviewer-SWHV` into `~/PUNCHStudio` and leaves the originals alone, so JHelioviewer keeps working beside it. The copy only happens while `~/PUNCHStudio` does not exist yet. Old sessions can always be opened directly with File > Load State.

## Licence, source code and bundled components

PUNCHStudio is released under the Mozilla Public License 2.0, the same licence as JHelioviewer (see [LICENSE](LICENSE)). Files that came from JHelioviewer keep that licence and their notices. The complete source of every release is this repository, and each entry on the Releases page carries the source it was built from.

The downloads also bundle libraries and native programs that carry their own licences, and the About dialog credits each of them. JPEG 2000 images are decoded by OpenJPEG under the BSD 2-clause licence, rather than by the proprietary Kakadu codec that JHelioviewer uses: that is what makes this fork's binaries ours to give away.

The name JHelioviewer appears here only to say where this software comes from. It belongs to its project, and the MPL grants no rights in it.

## Citing

If PUNCHStudio helps your research, please cite the JHelioviewer paper it is built on: Müller et al. (2017), Astronomy & Astrophysics, https://doi.org/10.1051/0004-6361/201730893. A dedicated PUNCHStudio methods paper is in prep for publication. 

## Reporting problems

Please report problems on this repository's [issue tracker](https://github.com/GillySpace27/PUNCHStudio/issues), or write to gilly@nwra.com. The JHelioviewer team did not write the code added here, so problems with PUNCHStudio should not go to them.

## Building from source

We build with Apache Ant and Java 25.

```bash
ant run
```

`ant run` compiles the application, packages `PUNCHStudio.jar` and starts it. `ant jar` stops after packaging, and `ant test` compiles and runs every self-check in `extra/test`. On macOS the build also compiles the small Metal host library in `native/macos` that the HDR canvas needs. Release packaging, signing and the field guide live in `release/`.

## Repository layout

`src` holds the application and `resources` its shaders, colour tables, settings and SPICE kernels. `lib` carries the bundled libraries and platform natives, and `native` the macOS Metal host. `extra` has build tools, test data and the self-checks, `docs` the design notes, and `release` the packaging and release tooling. `archive` keeps files that no longer belong to the application but are worth having on record; `archive/README.md` explains what is there.
