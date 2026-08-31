#!/usr/bin/env python3
"""Write a smooth-gradient FITS layer for judging canvas bit depth by eye.

A horizontal float32 ramp, 4096 steps across: far finer than either canvas can
resolve, so every visible band is the canvas quantizing, not the source. Open it
with File > Open Image, set a linear display range, and turn the dither off
(View > Dither Colour Banding). On the 8-bit canvas the ramp shows ~256 steps;
on the deep canvas (RGBA16F IOSurface; check the log for "Deep-colour canvas")
the steps should be invisible on a deep-capable panel.

Usage: python3 extra/test/make_gradient_fits.py [out.fits]
"""

import sys

import numpy as np
from astropy.io import fits

out = sys.argv[1] if len(sys.argv) > 1 else "gradient_test.fits"
ramp = np.linspace(0.0, 1.0, 4096, dtype=np.float32)
data = np.tile(ramp, (1024, 1))
hdu = fits.PrimaryHDU(data)
hdu.header["TELESCOP"] = "JHV-TEST"
hdu.header["INSTRUME"] = "GRADIENT"
hdu.header["DATE-OBS"] = "2026-01-01T00:00:00"
# Minimal helioprojective WCS: JHV refuses a layer without CDELT. One arcsec per pixel puts the
# ramp at about twice the Sun's width, a comfortable fill at default zoom.
hdu.header["CTYPE1"] = "HPLN-TAN"
hdu.header["CTYPE2"] = "HPLT-TAN"
hdu.header["CUNIT1"] = "arcsec"
hdu.header["CUNIT2"] = "arcsec"
hdu.header["CDELT1"] = 1.0
hdu.header["CDELT2"] = 1.0
hdu.header["CRPIX1"] = data.shape[1] / 2 + 0.5
hdu.header["CRPIX2"] = data.shape[0] / 2 + 0.5
hdu.header["CRVAL1"] = 0.0
hdu.header["CRVAL2"] = 0.0
hdu.writeto(out, overwrite=True)
print(f"wrote {out}: {data.shape[1]}x{data.shape[0]} float32 ramp, 4096 levels")
