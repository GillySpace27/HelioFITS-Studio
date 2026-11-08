# Starfield (the Stars overlay)

The Stars row under Overlays draws background stars, and optionally the planets, in the observer's
view. It is off by default.

## Data

- Catalogue: Hipparcos (ESA), stars with V < 9: 82,984 rows with a position (counted from the
  archive on 2026-11-07). Fetched on first use from the ESA Gaia archive's asynchronous TAP
  service (`https://gea.esac.esa.int/tap-server/tap/async`, table `public.hipparcos`) in six
  declination bands, then kept in `~/HFStudio/Catalogs/hipparcos-v9.csv`. Studio ships no
  catalogue. Delete that file to fetch it again.
- Licence: ESA distributes Hipparcos under CC BY-NC 3.0 IGO; the options panel shows the credit
  line "Credit: ESA".
- Why not Gaia: Gaia DR3 lacks the brightest stars (no Sirius, Vega, Betelgeuse, Regulus, Spica,
  Antares or Aldebaran in a cone search of the archive on 2026-11-07).

## Geometry

- Each star's ICRS direction (with proper motion from epoch J1991.25) is rotated to Carrington
  with `Spice.j2000ToSun` and projected to helioprojective (Tx, Ty) about the observer, as the
  older Gaia star code did. Planets use SPICE positions at the frame time, minus the observer's.
- Each body is kept as a direction (a unit ray in the observer's frame) and each view places it:
  the 3D views at (d tan Tx, d tan Ty) on the plane through Sun centre, HPC and Observer Sky
  through the page mapping the imagery uses.
- The 3D views leave out anything more than 80 degrees from the Sun, where that placement runs
  away. Observer Sky shows the whole sky; HPC shows the hemisphere toward the Sun.
- A planet nearer the observer than 0.25 solar radii, or within 1 arcsec of the Sun line, is
  taken to be the body the view is from and is not drawn. The second rule covers images with no
  observer position, which Studio places on the Sun-Earth line.
- Not applied: solar light deflection, parallax, aberration (arcsecond effects).
- Checked by `extra/test/StarSkyCheck.java` against sunpy's Helioprojective frame, and by eye
  against a LASCO C3 frame of 2026-08-22 00:30 UTC, where the drawn stars and Mercury fall on
  the real ones to within a few C3 pixels.

## Colour

B-V to temperature by Ballesteros (2012, EPL 97, 34008), then Planck's law at 610, 550 and
465 nm scaled to the brightest channel. A display aid, not colourimetry. Saturation 0 is white,
1 the blackbody colour, 2 exaggerated.

## Where it shows

The 3D-capable projections from the observer, HPC, and Observer Sky (any sky projection, aim and
field). Not drawn in Latitudinal or the unrolled helioradial map, which are maps of the Sun rather
than of the sky. In the orbited 3D camera the sky is at infinity: dragging turns each star's direction about the
viewer (the placement is redone every frame, so the sheet no longer swings edge-on) and the 80 degree
cut follows the camera.

The flat views re-place every point each frame, so a deep limit (V = 9, about 83,000 points) costs
more there than in the 3D views, which place them once per viewpoint.
