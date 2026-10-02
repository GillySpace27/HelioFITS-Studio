# RHEF conformance vectors

Small frames and the RHEF output they should give, for any RHEF implementation to test itself
against. In this repository `extra/test/RhefConformanceCheck.java` holds `FilterRHEF` to them.
HelioFITS (or any other port) can copy this folder as it is and read `manifest.json`: it is the
shared RHEF contract between HelioFITS and HelioFITS Studio.

Made once by `python3 extra/test/rhef/make_vectors.py` (needs numpy, scipy, astropy, sunpy and
sunkit-image; the versions used are in `manifest.json`). Never edit the files by hand; rerun the
generator with `--regenerate` and say so in the commit.

## Format

- `<case>.input.f32`, `<case>.expected.f32`: raw float32, little-endian, row-major, width x height
  from `manifest.json`, row 0 first, no header.
- `manifest.json`: `sunkit_image_version`, `tolerance` (1e-4), `crosscheck`, and per case `name`,
  `width`, `height`, `params`, `input`, `expected`.

## What the reference is

- Annuli: 1 pixel wide, centred on the frame centre, annulus = floor(hypot(x + 0.5 - W/2, y + 0.5 - H/2)).
  That is FilterRHEF's geometry when it has no sun-centre region.
- Rank rule: sunkit-image's, `scipy.stats.rankdata(values, method="average")` per annulus, the call
  `sunkit_image.radial.rhef(method="scipy")` makes. The generator checks itself against
  `sunkit_image.radial.rhef` on the radial-falloff frame before writing (`crosscheck` in the manifest).
- Conventions that are FilterRHEF's and not sunkit-image's, applied to the expected vectors:
  1. ranks are (R - 1) / (n - 1), so they run from 0 to 1; sunkit-image divides by n;
  2. only finite values above 0 are ranked; zeros, negatives and NaN keep their input value;
  3. an annulus with fewer than 5 ranked values is left as it was.
- Inputs are rounded to float16 first, because FilterRHEF ranks half floats.

## Cases

| Case | Size | What it tests |
|---|---|---|
| `radial-falloff-256` | 256 x 256 | a synthetic r^-3 falloff with azimuthal structure and noise: the rank rule and the annuli |
| `punch-crop-256` | 256 x 256 | a crop of `PUNCH_L3_CAM_20260425001600_v0k.fits` (rows H/2+872 to H/2+1128, columns W/2-128 to W/2+128), scaled to 0..1: a real value distribution and the half-float ties it makes |
| `ties-and-nans` | 96 x 80 | eight levels (ties in every annulus), 5% zeros, 5% NaN, 2% negatives: the tie rule and what is left alone |
| `upsilon-split` | 256 x 256 | the radial-falloff input against sunkit-image's own output, ranks over n and `apply_upsilon` at (0.35, 0.35), split at each annulus's nanmean. HelioFITS Studio's display splits at 0.5, so this case pins a known difference, not an agreement |

RHEF output is a visualization, not a calibrated radiance.
