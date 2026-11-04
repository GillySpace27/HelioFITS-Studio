# Loading data

Every source opens from one window: click **New Layer** in the sidebar, or choose
{menuselection}`Layers --> New Image Layer…` (Cmd-N). It has a tab per source (HV (JPEG 2000),
VSO (FITS), Direct (FITS) and, when there is a FITS library on this computer, Local FITS Library)
and opens on the tab you used last. {doc}`reference/data-sources` lists every archive, catalogue and
file format the app can load.

## Time range and time step

The window has its own time range and time step at the top, which every tab uses. They start as the
master ones beside New Layer each time it opens, and changing them affects only the layer being
added, never the master range. Tick **Frame count** to set how many frames instead, and the time step
follows. The line under it estimates the video length before anything downloads, so a long range at a
short step can be thinned before it costs you a large download.

## Streamed images or FITS

The HV (JPEG 2000) tab streams Helioviewer's browse images. They are fast, but they are 8-bit, and
no stretch can recover levels they never carried; that is where the terracing in a heavily filtered
layer comes from. When the faint structure matters, load FITS:

- **VSO (FITS)** queries the Virtual Solar Observatory for the calibrated FITS behind most missions
  (LASCO, EIT, AIA, HMI, SECCHI, XRT, EIS) at full bit depth, plus GOES SUVI and AIA channel by
  channel. Pick an instrument (for SUVI, **GOES > SUVI 195** and its neighbours), then **Add**.
- **Direct (FITS)** opens a mission's own archive (PUNCH, SOAR, ASPIICS, MLSO) or loads LASCO C2
  and C3 from NRL's level-0.5 archive, which is current where the VSO's LASCO catalogue is not.

The layer readout states the depth it measured from the pixels beside the depth the file claims, so
"16-bit (measured 2615 levels)" and "16-bit (measured 256 levels, 8-bit data)" tell you which one
you are looking at.

Before any FITS download, Studio looks in the FITS library for the same dataset over the same range.
If it is there, it offers the library's copy, with **Download Anyway** and **Cancel** beside it.

## PUNCH

PUNCH is NASA's four-spacecraft mission that images the corona and the young solar wind across a
wide field, from the low corona out toward Earth. Studio pulls PUNCH images from the public archive
and plays them as a movie.

- {menuselection}`Layers --> PUNCH Latest` loads the newest day of the clear average mosaic (CAM,
  Level 3) in the Helioradial projection with RHEF on, in one step.
- {menuselection}`Layers --> New PUNCH Layer…` chooses the level, product and range. For a first
  try choose **Level 3**, product **PTM** (the PTM and CTM trefoil mosaics are the high-cadence
  ones) and a one-day range, then **Search**, select frames and **Add**.

PUNCH data is reprocessed over time, and mixing two processing versions in one movie makes the
brightness jump from frame to frame. The **Version** box keeps one: **Latest** keeps the newest
single version, or you can name one; either way a movie is never a mix. Each PUNCH layer has a
refresh button in its row that re-checks the archive for newer frames, and the status bar shows the
time of the active PUNCH layer's newest frame and its age, in amber once it is more than a week old.
**PUNCH Live refresh**, in Settings and off by default, adds a **Live** box to PUNCH layers that
checks the archive every 15 minutes and loads new frames as a new layer.

## PROBA-3 ASPIICS

PROBA-3 is ESA's formation-flying pair: one spacecraft holds a disk about 144 m in front of the
other, casting a shadow onto the second one's telescope, an artificial total eclipse held for hours.
ASPIICS, the coronagraph behind that shadow, sees the corona from roughly 1.1 solar radii outward,
the band just above the limb that an ordinary coronagraph loses to its own occulter.

Open {menuselection}`Layers --> New ASPIICS Layer...`, choose an **Orbit** (each carries its own time
range), then **FITS** or **JP2**, and a product: **bt** (total brightness, the right first choice),
**pb** (polarized brightness), **pa** (polarization angle), **fe** (the Fe XIV green line) or **he**
(the He I D3 line). Click **Search**. Before selecting frames, set the **Cadence**: each frame is a
17 MB image, so a whole orbit at full rate runs to several gigabytes, and one frame a minute (or ten)
is usually what a movie needs. Then select frames and click **Add**.

## LASCO C2 and C3

A raw C3 frame is dominated by the static K-corona, F-corona and stray light, which is why an 8-bit
browse JPEG can look better than the 16-bit FITS behind it. For LASCO layers Studio fetches NRL's
monthly minimum images, interpolates the two that bracket each frame, and subtracts in DN before
anything is normalised, so the frames of a movie share one photometric footing. A LASCO layer's
**Background** section, between Display and Intensity, turns the subtraction on and off, chooses the
background under **Source**, and **Show as layer** adds the background itself as a layer of its own.

## Synoptic maps

A synoptic map is a whole Carrington rotation flattened into one 360° by 180° chart. Studio
recognises those maps and wraps them back onto the sphere, so you turn the globe and see each feature
where it sits, the far side included. The immediate case is the McIntosh Archive hand-drawn charts,
where an analyst has traced coronal holes, magnetic polarity, filaments and the neutral line. Those
are **categorical** images: a pixel carries a label, not a brightness, so Studio draws them with the
exact colours the analyst assigned and hides the controls that would corrupt a category into its
neighbour (Levels, Sharpen, Difference).

Open the FITS file with {menuselection}`Layers --> Open Image Layer...`; the map is recognised from
its own header and drawn with its own colour table. {menuselection}`Layers --> New Synoptic Layer...`
loads HMI and AIA synoptic maps from IAS IDOC ({doc}`reference/data-sources`).

```{figure} ../release/guide_assets/fig_synoptic.png
:width: 90%

A McIntosh Archive synoptic chart (CR2302) wrapped on the sphere: a negative-polarity coronal hole
in red with its magenta boundary, a positive one in blue, and filaments in green.
```

## Point clouds

{menuselection}`Layers --> New Point Cloud Layer...` drops a 3-D point cloud (a scattered set of
points in space, each optionally carrying a value) into the same view as your images, so a
reconstruction such as a modelled flux-rope surface or a triangulated CME front can be seen against
the imagery it came from. A file is a list of `[x, y, z]` points (with optional values) in
heliocentric coordinates, as `.json` or `.json.gz`; choosing several files at once loads them as a
time series that steps with the movie. The release carries a demo, `fabric_suvi.json.gz`, a rippled
sheet in the GOES-R SUVI field of view.

A cloud's options give **Points** with a **Size**; **Color by data** with a **Colormap**; the
**Alpha** slider, which wraps a surface around the points (right toward the convex hull, left to let
the folds open up; double-click to reset); **Wireframe** and **Surface** with an **Opacity**; and a
**sync** button that snaps the movie interval to the cloud's own time span.

(fits-library)=
## The FITS library

HelioFITS Core is the FITS library engine shared by the HelioFITS Suite programs; the HelioFITS Cache
Manager is the Mac app that downloads solar data into that library. The macOS build of Studio
carries its own copy of HelioFITS Core.

- When a library is on this computer, every FITS file Studio downloads is also added to it, and the
  **Local FITS Library** tab lists its datasets with how much of the time range each reaches. Its
  columns (Spacecraft, Instrument, Detector, Wavelength, Product, Level, Cadence) sort, and
  **Columns...** shows and hides them.
- A **FITS** button on each Helioviewer (JPEG 2000) layer's row asks HelioFITS Core for the FITS
  files behind the frames shown. **Fetch and open** gets the missing frames, at the layer's own
  cadence, and opens them as a new layer; **Open the N I have** skips the fetch. The newest frames may
  not be in the archives as FITS yet.
- **Use FITS** with no library yet offers to make one at `~/HelioFITSLibrary`.
- From the command line or SAMP, `-dataset` and `hfcore.dataset.load` load a library dataset as one
  layer ({doc}`reference/command-line`, {doc}`reference/samp`).

## Files of your own

Drop FITS or JPEG 2000 files anywhere on the window, or use
{menuselection}`Layers --> Open Image Layer...`. Files dropped on the window load themselves, whether they are imagery, a point
cloud or a saved session.
