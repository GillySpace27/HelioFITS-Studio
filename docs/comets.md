# Comets in the scene

How HelioFITS Studio shows a comet across image layers, and why the marks sit where they do.
Design: the comet3d plan, revision 2, section 2 (Gilly's project files, `comet-3d-scope/phase1-plan.md`).

## For users

Pick a comet in **Track comet** and press **Show in scene**. A comet layer appears in the scene
layers, in the next free comet colour.

- A ring marks where that image's spacecraft saw the comet at that image's time. Rings from
  different spacecraft are in different places: that is parallax, not an error.
- SOHO's position comes from JPL Horizons; SDO is placed at Earth; other spacecraft from their file
  headers. A layer whose header has no position is placed at Earth, and its ring is then a guess
  (the status line says so).
- No ring where the comet is outside that image, behind the Sun, or behind the occulter.
- **Light time** (on by default) places the comet where it was when the light left it. Near
  perihelion this moves it by tenths of a solar radius.
- **Refresh** asks JPL Horizons again, ignoring the saved answer, for a comet whose orbit is still
  being refined.
- The rings are drawn in the overlay pass, so they are in exported movies, and a layered export
  keeps them in the comet layer's own pass, never in an image's data channel.
- The comet layer is saved in the session (designation, colour, switches); its positions are not,
  and are fetched again (or read from the cache) when the session opens.

The status lines under the layer's options say how many positions came from Horizons, with the
orbit solution, and for each image layer where its observer came from and why it has no ring.

## Design

- `astronomy/CometEphemeris` holds the heliocentric path (Horizons state vectors, Hermite
  interpolation, cached on disk). It is fetched off the GL thread, only while the layer is on, for
  the movie's span plus one day each side (`CometEphemeris.window`).
- `identify/CometSight` is the geometry, with no GL, Swing or state: from the path, a layer's
  observer `Position`, its metadata and the time mode to a unit direction in the layer observer's
  frame (observer at +z, Sun at -z, solar north up) and a plane-of-sky point.
  - Time: the layer observer's time is DATE-OBS shifted by the time mode; `observationTime` undoes
    `FitsMetaData.adjustTime`, and both light time and the frame rotation use DATE-OBS.
  - Light time: tau(k+1) = t - |comet(tau(k)) - O| / c, at most four passes, positions in the frame
    at DATE-OBS.
  - Visibility: `Identifier.qualify`, the same rules as the identifier's bodies. `OCCULTED`,
    `BEHIND_SUN` and `OUTSIDE_FIELD` draw nothing.
- `layers/CometLayer` draws one ring per visible image layer in `renderFloat`, placed with
  `SkyScreen.place` and the layer's own observer, 7 px across scaled by the display's pixel scale and
  an export's capture scale.
- `identify/CometStyle` is the palette. The first colour is the Identify comet red as a constant;
  the others are provisional until agreed on a real frame.

## What the checks pin

`extra/test/CometSightCheck.java` and `extra/test/CometLayerCheck.java`. The end-to-end row compares
C/2025 R3 seen from SOHO with Horizons' own SOHO observer tables (S-O-T, PsAng and the Sun's NP.ang,
fixtures in `extra/test/data/`).

That row found a limit of the Track comet plane-of-sky marks (`Comets.parse`): they use
PsAng minus NP.ang as the angle at the Sun, but Horizons measures PsAng at the comet, from the
celestial north there. Near the Sun the two agree; away from it they do not. Over the C/2025 R3
window the marks are off by 87 arcsec at 13 Rsun, 532 arcsec at 23.5 Rsun and about 3.9 degrees at
30 degrees elongation (printed by CometSightCheck). The rings do not use that reduction.
