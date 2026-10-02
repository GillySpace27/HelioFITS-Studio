#!/usr/bin/env python3
"""Generate the RHEF conformance vectors in extra/test/data/rhef-conformance/.

Run once, from the repository root, with numpy, scipy, astropy, sunpy and sunkit-image installed:

    python3 extra/test/rhef/make_vectors.py              # refuses if the vectors exist
    python3 extra/test/rhef/make_vectors.py --regenerate # rewrites them; say so in the commit

Writes <case>.input.f32 and <case>.expected.f32 (raw little-endian float32, row-major, row 0
first) and manifest.json. extra/test/RhefConformanceCheck.java holds FilterRHEF to them.

The reference is sunkit-image's RHEF rank rule: within each annulus, scipy.stats.rankdata with
method="average", the call sunkit_image.radial.rhef(method="scipy") makes. The annuli are
FilterRHEF's: 1 pixel wide, centred on the frame centre,
annulus = floor(hypot(x + 0.5 - W/2, y + 0.5 - H/2)). Three conventions are FilterRHEF's, not
sunkit-image's, and are applied here so that the vectors test the ranking:
  1. ranks are (R - 1) / (n - 1), so 0 to 1; sunkit-image divides by n;
  2. only finite values above 0 are ranked; zeros, negatives and NaN keep their input value;
  3. an annulus with fewer than 5 ranked values is left as it was.
Inputs are rounded to float16 first, because FilterRHEF ranks half floats.

upsilon-split is different on purpose: its expected vector is sunkit-image's own output, ranks
over n and sunkit_image.utils.apply_upsilon per annulus, so the check can pin how far the display
upsilon is from it. That distance is mostly rank normalisation, not the split: FilterRHEF's own ranks,
(R - 1) / (n - 1), through the shader's split at 0.5 differ by max |d| 0.3078 (ranked pixels), and
converted to R / n first they differ by about 0.002 (float32 rounding of the top rank, amplified by the
0.35 power). The split alone, 0.5 against the annulus nanmean, is 5e-7 on rank data. See README.md here.

Before writing anything, sunkit_image.radial.rhef itself is run on the radial-falloff input and
must agree with the rank rule above (ranks over n) in every annulus that both assign the same
pixels to; otherwise nothing is written and the script exits 1.
"""
import json
import sys
from pathlib import Path

import numpy as np
import scipy
from scipy.stats import rankdata

import astropy.units as u
from astropy.coordinates import SkyCoord
from astropy.io import fits

import sunpy.map
from sunpy.coordinates import frames

import sunkit_image
from sunkit_image.radial import rhef
from sunkit_image.utils import apply_upsilon, find_pixel_radii

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "extra/test/data/rhef-conformance"
PUNCH = ROOT / "extra/test/data/PUNCH_L3_CAM_20260425001600_v0k.fits"
MIN_BIN_COUNT = 5      # FilterRHEF.MIN_BIN_COUNT
TOLERANCE = 1e-4
UPSILON = (0.35, 0.35)  # sunkit-image's default upsilon, as a pair


def half(a):
    """The values as FilterRHEF sees them: rounded to float16."""
    return np.asarray(a, dtype=np.float32).astype(np.float16).astype(np.float32)


def radius(h, w):
    y, x = np.mgrid[0:h, 0:w]
    return np.hypot(x + 0.5 - w / 2, y + 0.5 - h / 2)


def annuli(h, w):
    return np.floor(radius(h, w)).astype(np.int64)


def per_annulus(img, f):
    """f(values, n) on the finite positive values of every annulus with at least MIN_BIN_COUNT of them."""
    out = img.astype(np.float64).copy()
    ring = annuli(*img.shape)
    valid = np.isfinite(img) & (img > 0)
    for k in np.unique(ring):
        here = (ring == k) & valid
        n = int(here.sum())
        if n >= MIN_BIN_COUNT:
            out[here] = f(img[here], n)
    return out.astype(np.float32)


def jhv_ranks(img):
    return per_annulus(img, lambda v, n: (rankdata(v, method="average") - 1) / (n - 1))


def sunkit_ranks(img):
    return per_annulus(img, lambda v, n: rankdata(v, method="average") / n)


def sunkit_upsilon(img):
    return per_annulus(img, lambda v, n: apply_upsilon(rankdata(v, method="average") / n, UPSILON))


def radial_falloff(size=256):
    y, x = np.mgrid[0:size, 0:size]
    r = radius(size, size) + 4.0
    theta = np.arctan2(y + 0.5 - size / 2, x + 0.5 - size / 2)
    noise = np.random.default_rng(11).standard_normal((size, size))
    img = (r / 4.0) ** -3 * (1 + 0.2 * np.sin(7 * theta) + 0.05 * noise)
    return half(img / np.max(img))


def punch_crop(size=256):
    with fits.open(PUNCH) as hdul:
        data = next(h.data for h in hdul if h.data is not None and h.data.ndim == 2)
    cy, cx = data.shape[0] // 2 + 1000, data.shape[1] // 2
    crop = np.array(data[cy - size // 2:cy + size // 2, cx - size // 2:cx + size // 2], dtype=np.float64)
    finite = np.isfinite(crop)
    lo, hi = np.min(crop[finite]), np.max(crop[finite])
    return half((crop - lo) / (hi - lo))


def ties_and_nans(w=96, h=80):
    rng = np.random.default_rng(7)
    img = np.ceil(rng.random((h, w)) * 8) / 8   # eight levels: ties in every annulus
    img[rng.random((h, w)) < 0.05] = 0.0         # zeros stay zero
    img[rng.random((h, w)) < 0.05] = np.nan      # NaN stays NaN
    img[rng.random((h, w)) < 0.02] = -0.25       # negatives stay as they are
    return half(img)


def crosscheck(img):
    """sunkit_image.radial.rhef on img against sunkit_ranks(img), in annuli both bin alike."""
    h, w = img.shape
    coord = SkyCoord(0 * u.arcsec, 0 * u.arcsec, obstime="2026-04-25T00:16:00", observer="earth",
                     frame=frames.Helioprojective)
    smap = sunpy.map.Map(img, sunpy.map.make_fitswcs_header(img, coord, scale=[1, 1] * u.arcsec / u.pix))
    rsun = smap.rsun_obs.to_value(u.arcsec)  # 1 pixel = 1 arcsec here
    # The bins must reach past the largest pixel radius: otherwise rhef re-spaces ALL bins into
    # twice-as-wide ones (find_radial_bin_edges, sunkit-image 0.6.1), and nothing matches.
    n_bins = int(np.floor(radius(h, w).max())) + 1
    edges = np.array([np.arange(0, n_bins), np.arange(1, n_bins + 1)], dtype=float) / rsun * u.R_sun
    theirs = rhef(smap, radial_bin_edges=edges, upsilon=None, method="scipy", vignette=10 * u.R_sun).data
    their_ring = np.floor(find_pixel_radii(smap).to_value(u.R_sun) * rsun).astype(np.int64)
    ring = annuli(h, w)
    ours = sunkit_ranks(img)
    compared, worst = 0, 0.0
    for k in range(n_bins):
        here = ring == k
        if here.sum() < MIN_BIN_COUNT or not np.array_equal(here, their_ring == k):
            continue
        compared += int(here.sum())
        worst = max(worst, float(np.max(np.abs(theirs[here] - ours[here]))))
    return compared, worst


def write(name, array):
    np.asarray(array, dtype="<f4").tofile(OUT / name)
    return name


def main():
    if (OUT / "manifest.json").exists() and "--regenerate" not in sys.argv[1:]:
        sys.exit(f"{OUT / 'manifest.json'} exists; pass --regenerate to rewrite the vectors")
    falloff = radial_falloff()
    compared, worst = crosscheck(falloff)
    print(f"cross-check against sunkit_image.radial.rhef: {compared} pixels compared, max |difference| {worst:.2e}")
    if compared == 0 or worst > 1e-6:
        sys.exit("the rank rule here does not reproduce sunkit_image.radial.rhef; nothing written")

    OUT.mkdir(parents=True, exist_ok=True)
    conventions = {"normalisation": "(R - 1) / (n - 1)", "ranked": "finite and > 0", "minBinCount": MIN_BIN_COUNT,
                   "annulus": "floor(hypot(x + 0.5 - W/2, y + 0.5 - H/2))", "input": "rounded to float16"}
    cases = []
    for name, img, extra in [
        ("radial-falloff-256", falloff, {}),
        ("punch-crop-256", punch_crop(), {"source": PUNCH.name, "crop": "rows H/2+872 to H/2+1128, columns W/2-128 to W/2+128, scaled to 0..1"}),
        ("ties-and-nans", ties_and_nans(), {}),
    ]:
        h, w = img.shape
        cases.append({"name": name, "width": w, "height": h, "params": {**conventions, **extra},
                      "input": write(f"{name}.input.f32", img),
                      "expected": write(f"{name}.expected.f32", jhv_ranks(img))})
    h, w = falloff.shape
    cases.append({"name": "upsilon-split", "width": w, "height": h,
                  "params": {**conventions, "normalisation": "R / n", "upsilon": list(UPSILON),
                             "split": "annulus nanmean (sunkit_image.utils.apply_upsilon)"},
                  "input": "radial-falloff-256.input.f32",
                  "expected": write("upsilon-split.expected.f32", sunkit_upsilon(falloff))})

    manifest = {"sunkit_image_version": sunkit_image.__version__, "numpy_version": np.__version__,
                "scipy_version": scipy.__version__, "tolerance": TOLERANCE,
                "generator": "extra/test/rhef/make_vectors.py",
                "crosscheck": {"pixels": compared, "max_abs_diff": worst}, "cases": cases}
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    total = sum(p.stat().st_size for p in OUT.iterdir())
    print(f"wrote {len(cases)} cases, {total} bytes in {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
