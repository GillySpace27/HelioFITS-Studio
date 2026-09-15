# HelioFITS Studio

HelioFITS Studio is a desktop viewer for solar and heliospheric imagery, built around coronagraph and wide-field data such as NASA's PUNCH mission, SOHO/LASCO and PROBA-3/ASPIICS. It is a fork of JHelioviewer, the open-source solar image browser of the ESA/NASA Helioviewer Project. We kept JHelioviewer's 3-D view of the Sun, its timelines and its event overlays, and added the tools we needed to work with the outer corona.

## What it adds

- PUNCH data straight from the SDAC archive, named by product and pipeline version, with a shared display range so that a movie does not strobe from frame to frame.
- Native LASCO C2 and C3 FITS from NRL, with monthly background subtraction, the correct orientation on either side of SOHO's roll flips, and pointing recovered for frames whose headers lost it.
- A Sun-centred Helioradial view and its unrolled form, where a single control trades linear distance for compression of the outer corona.
- The RHEF radial histogram equalizing filter, sequence filters, CME tracking against the CACTus catalog, Carrington synoptic maps and point clouds.
- An extended dynamic range canvas on Macs that support it, and deep-colour movie, PNG and EXR export.

## Installing

On an Apple Silicon Mac, the simplest route is the signed and notarized `.dmg` from the [Releases page](https://github.com/GillySpace27/JHelioviewer-SWHV/releases). It carries its own Java runtime, so there is nothing else to install.

Everywhere else, download the `.zip` from the same page, install Java 25 or newer (for example Temurin 25 from [adoptium.net](https://adoptium.net), or `brew install openjdk@25`), and start `run.command` on macOS, `run.sh` on Linux or `run.bat` on Windows. Note that only macOS has been tested so far; the Linux and Windows launchers are included, but we have not yet tried them on those systems.

## Coming from JHelioviewer

The first time it runs, HelioFITS Studio copies your settings and saved states from `~/JHelioviewer-SWHV` into `~/HFStudio` and leaves the originals alone, so JHelioviewer keeps working beside it. The copy only happens while `~/HFStudio` does not exist yet. Old sessions can always be opened directly with File > Load State.

## Building from source

We build with Apache Ant and Java 25.

```bash
ant run
```

`ant run` compiles the application, packages `HFStudio.jar` and starts it. `ant jar` stops after packaging, and `ant test` compiles and runs every self-check in `extra/test`. On macOS the build also compiles the small Metal host library in `native/macos` that the extended dynamic range canvas needs. Release packaging, signing and the field guide live in `release/`.

## Repository layout

`src` holds the application and `resources` its shaders, colour tables, settings and SPICE kernels. `lib` carries the bundled libraries and platform natives, and `native` the macOS Metal host. `extra` has build tools, test data and the self-checks, `docs` the design notes, and `release` the packaging and release tooling. `archive` keeps files that no longer belong to the application but are worth having on record; `archive/README.md` explains what is there.

## Reporting problems

Please report problems on this repository's [issue tracker](https://github.com/GillySpace27/JHelioviewer-SWHV/issues). The JHelioviewer team did not write the code added here, so problems with HelioFITS Studio should not go to them.

## Licence and credits

HelioFITS Studio is released under the Mozilla Public License 2.0, the same licence as JHelioviewer (see `LICENSE`). JHelioviewer is developed by the ESA JHelioviewer team as part of the ESA/NASA Helioviewer Project and was enhanced at ROB/SIDC; HelioFITS Studio is not affiliated with or endorsed by that project. The bundled third-party components and their licences are described in `THIRD-PARTY.md`.

If HelioFITS Studio helps your research, please also cite the JHelioviewer paper by Müller et al. (2017), Astronomy & Astrophysics, https://doi.org/10.1051/0004-6361/201730893.
