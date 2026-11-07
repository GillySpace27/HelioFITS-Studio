"""Oracle for IdentifyCheck.checkPunchFixture: each body's helioprojective position from the CAM fixture's own observer (HGLN_OBS, HGLT_OBS, DSUN_OBS) and whether astropy WCS puts it on the fixture's pixels. Usage: identify_cam_oracle.py <de432s_reduced.bsp> <PUNCH_L3_CAM fixture>."""
import sys, warnings
warnings.simplefilter('ignore')
import astropy.units as u
from astropy.io import fits
from astropy.wcs import WCS
from astropy.coordinates import solar_system_ephemeris, SkyCoord
from astropy.time import Time
from sunpy.coordinates import frames, get_body_heliographic_stonyhurst
solar_system_ephemeris.set('file://' + sys.argv[1])
with fits.open(sys.argv[2]) as hdul:
    h = hdul[1].header
for k in ['DATE-OBS','DATE-AVG','HGLN_OBS','HGLT_OBS','DSUN_OBS','CRLN_OBS','CTYPE1','CDELT1','NAXIS1','ZNAXIS1','CRPIX1','CRPIX2','CRVAL1','CRVAL2','PC1_1','PC1_2','RSUN_REF']:
    print(k, h.get(k))
w = WCS(h, key=' ').celestial
obs_time = Time(h['DATE-AVG'])  # Studio prefers DATE-AVG for a frame's time
obs = SkyCoord(h['HGLN_OBS']*u.deg, h['HGLT_OBS']*u.deg, h['DSUN_OBS']*u.m, frame=frames.HeliographicStonyhurst, obstime=obs_time).frame
nx = h.get('ZNAXIS1', h['NAXIS1']); ny = h.get('ZNAXIS2', h['NAXIS2'])
for b in ['mercury','venus','moon','mars','jupiter','saturn','uranus','neptune']:
    c = SkyCoord(get_body_heliographic_stonyhurst(b, obs_time)).transform_to(frames.Helioprojective(observer=obs, obstime=obs_time))
    px, py = w.world_to_pixel(c)
    inside = -0.5 <= px < nx-0.5 and -0.5 <= py < ny-0.5
    print(f'{b:8s} Tx {c.Tx.to_value(u.deg):9.4f} Ty {c.Ty.to_value(u.deg):9.4f} px {float(px):8.1f} {float(py):8.1f} inside {inside}')
