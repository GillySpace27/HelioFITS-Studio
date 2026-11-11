"""Block-median reduce the 2025-08-14 ~09:00 UTC multi-instrument frames into resources/samples (demo scene).

usage: python3 extra/tools/make_demo_samples.py RAW_DIR OUT_DIR
RAW_DIR holds the original files named in resources/samples/README.md; the reduction is
n x n block median (NaN-aware), not decimation, with CDELT/CRPIX (and the pixel-valued keys listed
below) rescaled so the WCS of every frame still lands on the same Sun.
"""
import os
import sys
import warnings
import numpy as np
from astropy.io import fits

# (source file, HDU, n, output name, pixel-valued keys that scale by 1/n, keys to drop)
SAMPLES = [
    ('AIA20250814_0900_0171.fits', 1, 2, 'aia_171_20250814T090009_512.fits', [], ['BSCALE', 'BZERO', 'BLANK']),  # R_SUN, X0_MP set below
    ('AIA20250814_0900_0193.fits', 1, 2, 'aia_193_20250814T090004_512.fits', [], ['BSCALE', 'BZERO', 'BLANK']),
    ('c2_22999194.fts', 0, 2, 'lasco_c2_20250814T090005_512.fits', [], []),
    ('c3_32829445.fts', 0, 2, 'lasco_c3_20250814T085406_512.fits', [], []),
    ('PUNCH_L3_CAM_20250814084800_v0m.fits', 1, 8, 'punch_cam_20250814T084800_512.fits', [], []),
    ('aspiics_bt_l3_20250814T090012_1340803F0006_v03.fits', 0, 4, 'aspiics_bt_20250814T090012_512.fits',
     [], ['X_IO', 'Y_IO']),  # occulter centre in pixels, convention unchecked: dropped, not guessed
]
DROP = ('CHECKSUM', 'DATASUM', 'HEADSUM', 'ZIMAGE', 'ZBITPIX', 'ZNAXIS', 'ZNAXIS1', 'ZNAXIS2', 'ZTILE1', 'ZTILE2',
        'ZCMPTYPE', 'ZNAME1', 'ZVAL1', 'ZNAME2', 'ZVAL2', 'ZQUANTIZ', 'ZDITHER0', 'ZSIMPLE', 'ZEXTEND',
        'ZHECKSUM', 'ZDATASUM', 'ZPCOUNT', 'ZGCOUNT', 'PCOUNT', 'GCOUNT', 'EXTNAME')


def reduce(src, hdu, n, dst, pix, drop):
    with fits.open(src) as h:
        hdr = h[hdu].header.copy()
        data = h[hdu].data
    is_int = data.dtype.kind == 'i' and 'BSCALE' not in hdr
    data = data.astype(np.float64)
    data[~np.isfinite(data)] = np.nan  # ASPIICS L3 carries inf pixels, which wreck the display clip
    ny, nx = data.shape
    assert ny % n == 0 and nx % n == 0, (src, data.shape, n)
    with warnings.catch_warnings():
        warnings.simplefilter('ignore', RuntimeWarning)  # all-NaN blocks stay NaN
        out = np.nanmedian(data.reshape(ny // n, n, nx // n, n), axis=(1, 3))
    out = np.clip(np.rint(out), -32767, 32767).astype(np.int16) if is_int else out.astype(np.float32)
    for k in list(hdr.keys()):  # CDELT/CRPIX, with and without an alternate-WCS letter
        for ax in '12':
            if k in ('CDELT' + ax, 'CDELT' + ax + 'A'):
                hdr[k] = hdr[k] * n
            elif k in ('CRPIX' + ax, 'CRPIX' + ax + 'A'):
                hdr[k] = (hdr[k] - 0.5) / n + 0.5
    for k in pix:
        hdr[k] = hdr[k] / n
    if 'X0_MP' in hdr:  # AIA synoptic: R_SUN and X0_MP are still the 4096 level 1 values, so set from the WCS
        hdr['R_SUN'] = hdr['RSUN_OBS'] / hdr['CDELT1']
        hdr['X0_MP'] = hdr['CRPIX1'] - 1  # 0-based pixel centre of the Sun
        hdr['Y0_MP'] = hdr['CRPIX2'] - 1
    for k in DROP + tuple(drop):
        hdr.remove(k, ignore_missing=True, remove_all=True)
    hdr['HISTORY'] = 'HelioFITS Studio demo sample: %dx%d block median (NaN-aware) of %s,' % (n, n, os.path.basename(src))
    hdr['HISTORY'] = 'with CDELT, CRPIX and pixel-valued keys scaled to match. Not for science.'
    fits.HDUList([fits.PrimaryHDU(), fits.CompImageHDU(out, header=hdr, compression_type='RICE_1')]).writeto(
        dst, overwrite=False, output_verify='silentfix')
    print(dst, out.shape, out.dtype, os.path.getsize(dst))


if __name__ == '__main__':
    raw, outdir = sys.argv[1], sys.argv[2]
    for s in SAMPLES:
        reduce(os.path.join(raw, s[0]), s[1], s[2], os.path.join(outdir, s[3]), s[4], s[5])
