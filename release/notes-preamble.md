### About @APP_NAME@

@APP_NAME@ is a fork of JHelioviewer, the open-source solar image browser from the ESA/NASA
Helioviewer Project. It streams decades of full-disk and coronagraph imagery from the major
solar observatories and plays it back as movies. It is maintained separately from JHelioviewer,
so please report problems and requests on this repository's issue tracker:
https://github.com/@REPO@/issues

### Why a fork

We work with NASA's PUNCH mission and the wider coronagraph record, and JHelioviewer did not do
several things that work needed: load PUNCH data, stretch the outer corona so it has room to read,
equalize the steep radial falloff, and a few more. @APP_NAME@ adds them. Several of those pieces
have since been taken into JHelioviewer's own development line (the PUNCH layer, RHEF, the
Helioradial projections and the grid colour controls), and others were submitted there as pull
requests. Earlier builds were published here as the JHelioviewer PUNCH & Coronal Research
Distribution (v5.6a to v5.6d); @APP_NAME@ continues that line under its own name. The README on
this repository explains the fork, its relationship to JHelioviewer and its licensing in full.

### What @APP_NAME@ adds

**Projections**
- **Helioradial**: a Sun-centered radial re-stretch that gives the outer corona room, on one knob from linear through logarithmic to inverse. The disk itself is left unwarped.
- **Helioradial Unrolled**: the same radial stretch unrolled into a position-angle strip (earlier-stage draft).
- The redundant Polar and LogPolar projections are removed, subsumed by these.
- **Observer Sky** (experimental): a projection that looks out from the observer's own position instead of at the Sun, with the coronagraph reference surfaces along for the ride.

**Data sources**
- **PUNCH**: load NASA PUNCH mosaics from the public archive and play them as movies. (also merged into JHelioviewer, Helioviewer-Project/JHelioviewer-SWHV#328)
- **PROBA-3 / ASPIICS**: ESA's formation-flying coronagraph; loads an orbit's frames, with a cadence control and a confirmation before a multi-gigabyte download.
- A PUNCH pipeline-version selector (keeps a movie to one calibration), a per-layer archive-refresh button, and a shared display range that stops PUNCH movies strobing.
- **Coronagraph and EUV data at full depth.** LASCO C2 and C3 straight from NRL's level-0.5 archive (the VSO's LASCO catalogue stops in early 2025; NRL's is current), GOES SUVI as native L1b per channel, and AIA per channel. The layer readout states the depth measured from the pixels beside the depth the file claims, so an 8-bit browse product is named as one however wide the buffer holding it.
- **Native FITS from the VSO**: a FITS (VSO) card on the add-layer button pulls calibrated full-bit-depth FITS for most missions (LASCO, EIT, AIA, HMI, SECCHI, XRT, EIS), plus **GOES SUVI** channel by channel as native L1b, for when the 8-bit JP2 browse products band under a hard stretch.

**Image processing**
- **RHEF**: the Radial Histogram Equalization Filter, with an Upsilon control for shadows and highlights. (also taken into JHelioviewer's development line)
- **C3 with its background removed.** NRL's monthly minimum images are fetched automatically, the two bracketing each frame interpolated as their own getbkgimg.pro does, and subtracted in DN before normalization, which also puts a movie's frames on one photometric footing.

**Display**
- **HDR canvas (macOS)**: image layers render into the display's extended range, so the corona can be brighter than the window. Needs an EDR display.

**Overlays**
- Adjustable coordinate grid: color, opacity, line width, label size, and radial-label angle.

**CME tracking**
- Pick a CACTus eruption and hold its leading front at a fixed screen radius while it propagates, so you watch it evolve rather than recede.

**Point clouds**
- **Point Cloud layer**: render a scattered 3D point cloud over the Sun, colored by a per-point value, with an alpha-shape surface slider that recovers a folded surface from the points (convex hull at 100 %, the folds resolving as you drag down). Load the included `fabric_suvi.json.gz` demo; a rippled sheet placed in the GOES-R SUVI field of view; via the layer's **Open…** button.

**Interface**
- Reorganized layer panel: a full-width docked transport bar, a collapsible sidebar, and nested layer options.
- Discoverable timeline trim and move gestures.
- **Imagery appears while the rest is still loading** instead of after the last frame, and frames with no picture in them (SUVI darks, LASCO's daily filter sequence) are recognized and replaced by the nearest good frame.
- **Truthful export framing**: output size is an aspect plus a long side, and locking the aspect letterboxes the canvas to exactly what the export will contain.
- **Quality of life**: a small clock dial on the timestamp overlay; the colour legend as a true gradient; layers added from the File menu follow the master range including cadence; a second running instance degrades to a memory-only cache instead of flooding the log; saving a session by hand pulls its data down; assorted smaller fixes.

**Reliability**
- Gzipped FITS from NOAA load; VSO queries ask for the one channel wanted, a day at a time, so a SUVI query takes seconds rather than appearing to hang; an unreachable IPv6 address no longer fails PUNCH loads at random; files dropped on the window load themselves.

**Packaging**
- Signed and notarized macOS `.app` with an embedded Java runtime, so it opens with no security warning and needs no separate Java install.
