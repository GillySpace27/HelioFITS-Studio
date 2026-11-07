"""Oracle for IdentifyCheck.checkTransit: when the Moon enters and leaves a 46.08 deg square field from Earth, 2025-09-10 to 10-05, sampled every 10 minutes with sunpy and the bundled de432s_reduced kernel."""
import sys, numpy as np
import astropy.units as u
from astropy.coordinates import solar_system_ephemeris, SkyCoord
from astropy.time import Time
from sunpy.coordinates import frames, get_body_heliographic_stonyhurst
solar_system_ephemeris.set('file://' + sys.argv[1])
t0 = Time('2025-09-10T00:00:00'); n = int(25*24*6)+1
times = t0 + np.arange(n) * 10 * u.min
earth = get_body_heliographic_stonyhurst('earth', times)
moon = get_body_heliographic_stonyhurst('moon', times)
hpc = SkyCoord(moon).transform_to(frames.Helioprojective(observer=earth, obstime=times))
tx = hpc.Tx.to_value(u.deg); ty = hpc.Ty.to_value(u.deg)
inside = (np.abs(tx) <= 46.08) & (np.abs(ty) <= 46.08)
for i in range(1, n):
    if inside[i] != inside[i-1]:
        print(('enter' if inside[i] else 'exit'), times[i-1].isot, times[i].isot)
