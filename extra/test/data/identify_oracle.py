"""Independent oracle for IdentifyCheck: sunpy/astropy helioprojective positions of bodies.

Ephemeris: the same de432s_reduced kernel Studio bundles, read by jplephem through astropy.
Prints Java-ready rows: time, observer kind, body, Tx deg, Ty deg (geometric) and Tx, Ty with light time.
"""
import sys
import astropy.units as u
from astropy.coordinates import solar_system_ephemeris, SkyCoord
from astropy.time import Time
from sunpy.coordinates import frames, get_body_heliographic_stonyhurst

kernel = sys.argv[1]
solar_system_ephemeris.set('file://' + kernel)

cases = [
    ("2025-09-22T05:46:30", "earth", ["moon", "venus", "mercury", "mars", "sun"]),
    ("2025-09-21T16:02:00", "earth", ["moon"]),
    ("2025-09-24T04:58:00", "earth", ["moon", "venus"]),
    ("2026-03-26T12:00:00", "earth", ["saturn", "moon"]),
    ("2025-09-22T05:46:30", "made_up", ["moon", "venus", "earth", "mars", "jupiter"]),
]

for t, obs_kind, bodies in cases:
    time = Time(t, scale='utc')
    earth = get_body_heliographic_stonyhurst('earth', time)
    if obs_kind == 'earth':
        obs = earth
    else:
        obs = SkyCoord(-90 * u.deg, 7 * u.deg, 0.3 * u.AU, frame=frames.HeliographicStonyhurst, obstime=time).frame
    hpc = frames.Helioprojective(observer=obs, obstime=time)
    for b in bodies:
        geo = get_body_heliographic_stonyhurst(b, time)
        g = SkyCoord(geo).transform_to(hpc)
        app = get_body_heliographic_stonyhurst(b, time, observer=obs, quiet=True)
        a = SkyCoord(app).transform_to(hpc)
        print(f'row("{t}", "{obs_kind}", "{b.capitalize()}", {g.Tx.to_value(u.deg):.6f}, {g.Ty.to_value(u.deg):.6f}, {a.Tx.to_value(u.deg):.6f}, {a.Ty.to_value(u.deg):.6f}),')
