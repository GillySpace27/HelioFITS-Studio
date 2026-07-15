JHelioviewer - PUNCH & Coronagraph Preview Build
================================================

This is an UNOFFICIAL preview build of JHelioviewer (JHV), the open-source solar
image browser from the Helioviewer Project. It adds features being contributed
back upstream and currently in review: NASA PUNCH mission data, PROBA-3/ASPIICS,
a Sun-centered coronagraph view (RadialWarp), the RectWarp unwrap, the RHEF
radial enhancement filter, CME tracking, and grid styling.

  >> Full walkthrough: open  JHV-Preview-Guide.pdf


ON macOS, THE EASIER OPTION IS THE .dmg
  If you are on an Apple Silicon Mac, download  JHelioviewer-PUNCH-preview.dmg
  instead of this zip. It is signed and notarized (so it opens with no security
  warning) and carries its own Java runtime, so you do not need to install Java.
  Open the .dmg, drag JHelioviewer to Applications, and double-click.

  This zip is for other platforms, older Intel Macs, or if you prefer to run from
  your own Java.


REQUIREMENTS (for this zip)
  Java 25 or newer.
    - any system:       https://adoptium.net   (download "Temurin 25")
    - macOS + Homebrew:  brew install openjdk@25


HOW TO RUN
  macOS:    double-click  run.command   (or run ./run.command in a Terminal here)
  Linux:    ./run.sh      from a terminal in this folder
  Windows:  double-click  run.bat

  The launcher finds your Java 25 automatically. If it cannot, it will tell you
  how to install it.

  macOS note: the first time, macOS may say the file is from an unidentified
  developer. Right-click run.command and choose Open (once), or run
  "xattr -dr com.apple.quarantine ." in this folder. (The .dmg above avoids this.)


ABOUT
  Based on JHelioviewer 5.6.0; licensed under MPL 2.0, the same as JHV itself.
  Unofficial and lightly tested. Please send problems to Chris Gilly (NWRA), not
  to the JHV team. The features are in review upstream as pull requests at
  github.com/Helioviewer-Project/JHelioviewer-SWHV.
