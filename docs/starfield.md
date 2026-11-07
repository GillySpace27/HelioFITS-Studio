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
- Anything more than 80 degrees from the Sun as seen by the observer is not drawn.
- Not applied: solar light deflection, parallax, aberration (arcsecond effects).
- Checked by `extra/test/StarSkyCheck.java` against sunpy's Helioprojective frame, and by eye
  against a LASCO C3 frame of 2026-08-22 00:30 UTC, where the drawn stars and Mercury fall on
  the real ones to within a few C3 pixels.

## Colour

B-V to temperature by Ballesteros (2012, EPL 97, 34008), then Planck's law at 610, 550 and
465 nm scaled to the brightest channel. A display aid, not colourimetry. Saturation 0 is white,
1 the blackbody colour, 2 exaggerated.

## Where it shows

The 3D-capable projections, from the observer (the stars sit on the plane through Sun centre normal
to the line of sight). Not yet: Observer Sky, HPC and the other flat projections, and the
orbited camera.
