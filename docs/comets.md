# Comets in the scene

How HelioFITS Studio shows a comet across image layers, and why the marks sit where they do.
Design: the comet3d plan, revision 2, sections 2 and 3 (Gilly's project files, `comet-3d-scope/phase1-plan.md`).

## For users

Pick a comet in **Track comet** and switch on **Show in 3D scene** (or use **Add layer** in the 3D Scene pane). A comet layer appears in the scene
layers, in the next free comet colour. Switching the toggle off turns the layer off without removing it.

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
- In the 3D modes (Orthographic and Helioradial in 3D) the layer also draws the comet's **Orbit**,
  brighter where it has been and fainter where it is going, and its **Position**: a dot where it
  is at the display time. The orbit covers the movie and a span of days each side of perihelion
  (30 by default; set beside the Orbit box), or each side of the movie for a comet with no
  perihelion date, at most three years.
- The dot and orbit are the comet's true position in 3D. The images are perspective views from
  each spacecraft, so the dot does not sit on a ring unless the comet is in the image's plane of
  sky: for a comet at depth z toward the observer the ring is `d/(d-z)` times as far from Sun
  centre as the dot looks in a view from that spacecraft: 1.03 at 6 Rsun, 1.16 at 30 Rsun, 1.99 at
  half an au (computed: `python3 -c "d=215.03;print([round(d/(d-z),3) for z in (6,30,107)])"`).
- GridLayer's planets have a "follow solar rotation" switch; the comet always follows it, because
  its rings must stay on the images.
- The comet layer is saved in the session (designation, colour, switches, orbit span); its
  positions are not, and are fetched again (or read from the cache) when the session opens.

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
- The orbit (`CometLayer.render`, 3D modes only) is the path's samples with eight Hermite
  sub-chords between each pair (`CometLayer.orbitTimes`), all rotated with one matrix at the
  display time (`CometEphemeris.worldAll`), as `PlanetMarkers.buildOrbits` turns the planets'
  orbits. The display time is a vertex, where the line changes from past to future. The dot is
  the geometric position at the display time (no light time), a point of 0.012 of the camera
  width, drawn without the depth test as the grid's planets are. With the orbit on, the comet's
  path is fetched over the orbit window; SOHO's states only over the movie.
- `identify/CometStyle` is the palette. The first colour is the Identify comet red as a constant;
  the others are provisional until agreed on a real frame.

## What the checks pin

`extra/test/CometSightCheck.java`, `extra/test/CometLayerCheck.java` and
`extra/test/CometOrbitCheck.java` (the orbit's frame against SPICE's Earth and the grid's planets,
the window rules, the sub-chords, the past and future split). The end-to-end row compares
C/2025 R3 seen from SOHO with Horizons' own SOHO observer tables (S-O-T, PsAng and the Sun's NP.ang,
fixtures in `extra/test/data/`).

That row found an error in the Track comet plane-of-sky track (`Comets.parse`), since fixed: it
used PsAng minus NP.ang as the angle at the Sun, but Horizons measures PsAng at the comet, from the
celestial north there. Near the Sun the two agree; away from it they do not. Over the C/2025 R3
window the old track was off by 66 arcsec at 12.8 Rsun, 532 arcsec at 23.5 Rsun and about 3.9 degrees
at 30 degrees elongation. `Comets.parse` now solves for the angle at the Sun (`Comets.bearingAtSun`),
with the Sun's direction from SOHO's Horizons states (one more request per window, cached; Earth's
direction would be up to 100 arcsec off). `extra/test/CometTrackAngleCheck.java` pins the track
against the state vectors at 12.8 Rsun, 23.5 Rsun and 44.6 degrees elongation.
