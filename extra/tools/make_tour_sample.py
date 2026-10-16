"""Rebin the AIA 171 test fixture 4x4 into the bundled tour sample (resources/samples)."""
import sys
import numpy as np
from astropy.io import fits

src, dst, n = sys.argv[1], sys.argv[2], int(sys.argv[3])
with fits.open(src) as h:
    hdr = h[1].header.copy()
    data = h[1].data.astype(np.float64)
blank = hdr.get('BLANK')
ny, nx = data.shape
b = data.reshape(ny // n, n, nx // n, n)
out = np.nanmean(b, axis=(1, 3))
out = np.clip(np.rint(out), -32767, 32767).astype(np.int16)
for ax in ('1', '2'):
    hdr['CDELT' + ax] = hdr['CDELT' + ax] * n
    hdr['CRPIX' + ax] = (hdr['CRPIX' + ax] - 0.5) / n + 0.5
for k in ('X0_MP', 'Y0_MP'):  # 0-based pixel centre
    if k in hdr:
        hdr[k] = (hdr[k] + 0.5) / n - 0.5
if 'R_SUN' in hdr:
    hdr['R_SUN'] = hdr['R_SUN'] / n
for k in list(hdr.keys()):
    if k in ('ZIMAGE', 'ZBITPIX', 'ZNAXIS', 'ZNAXIS1', 'ZNAXIS2', 'ZTILE1', 'ZTILE2', 'ZCMPTYPE', 'ZNAME1', 'ZVAL1', 'ZNAME2', 'ZVAL2', 'ZQUANTIZ', 'ZDITHER0', 'ZSIMPLE', 'ZEXTEND', 'ZHECKSUM', 'ZDATASUM', 'CHECKSUM', 'DATASUM'):
        hdr.remove(k, ignore_missing=True, remove_all=True)
hdr['HISTORY'] = 'HelioFITS Studio tour sample: %dx%d mean rebin of the AIA level 1 file,' % (n, n)
hdr['HISTORY'] = 'with CDELT, CRPIX, X0_MP, Y0_MP and R_SUN scaled to match. Not for science.'
hdu = fits.CompImageHDU(out, header=hdr, compression_type='RICE_1')
fits.HDUList([fits.PrimaryHDU(), hdu]).writeto(dst, overwrite=False, output_verify='silentfix')
print(dst, out.shape, out.dtype, out.min(), out.max())
