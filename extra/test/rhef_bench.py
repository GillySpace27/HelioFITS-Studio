"""Time sunkit-image's RHEF on exactly the array JHV's RHEF was timed on, and compare outputs.

Fair-comparison choices, all stated because each one moves the number:
  - same input pixels (punch_normalised.f32, written by the Java harness)
  - same annuli: 1-pixel-wide, which is what JHV uses; passed explicitly so sunkit does not
    choose its own binning
  - upsilon=None, so sunkit returns the rank itself and not a gamma-shaped rank, matching
    what JHV's filter returns
  - the timer covers only the rhef call, not building the map
"""
import time, numpy as np, astropy.units as u
from astropy.io import fits
import sunpy.map, sunkit_image, sunkit_image.radial as radial
from scipy import __version__ as scipy_version

W = 4096
SRC = "/Users/gilly/HFStudio/FileCache/ac078ff0288c51fd3bb85bb64db1579117172c951c529a18b417c94809119d0a"

data = np.fromfile("punch_normalised.f32", dtype=np.float32).reshape(W, W)
java = np.fromfile("punch_rhef_java.f32", dtype=np.float32).reshape(W, W)

hdr = None
for u_ in fits.open(SRC):
    if u_.data is not None and getattr(u_.data, "shape", None) == (W, W):
        hdr = u_.header
        break
smap = sunpy.map.Map(data.astype(np.float64), hdr)

rsun_px = hdr["RSUN_ARC"] / 3600 / hdr["CDELT1"]          # 11.802 px per R_sun
max_r = np.hypot(W / 2, W / 2) / rsun_px                   # corner, in R_sun
step = 1 / rsun_px                                         # one pixel, in R_sun
edges_1d = np.arange(0, max_r + step, step)
edges = np.vstack((edges_1d[:-1], edges_1d[1:])) * u.R_sun
print(f"sunkit_image {sunkit_image.__version__}, sunpy {sunpy.__version__}, scipy {scipy_version}, numpy {np.__version__}")
print(f"annuli: {edges.shape[1]} of width {step * rsun_px:.2f} px")

for method in ("scipy", "numpy", "inplace"):
    try:
        radial.rhef(smap, radial_bin_edges=edges, upsilon=None, method=method)  # warm
        t = time.perf_counter()
        out = radial.rhef(smap, radial_bin_edges=edges, upsilon=None, method=method)
        dt = time.perf_counter() - t
        print(f"  sunkit rhef method={method:8s}  {dt * 1000:9.1f} ms")
        if method == "scipy":
            best = np.asarray(out.data, dtype=np.float32)
    except Exception as e:
        print(f"  sunkit rhef method={method:8s}  FAILED: {type(e).__name__}: {e}")

m = np.isfinite(best) & np.isfinite(java) & (data > 0)
d = np.abs(best[m] - java[m])
print(f"\ncompared where both finite and value > 0: {m.sum()} px")
print(f"  median |sunkit - java| {np.median(d):.4f}   mean {d.mean():.4f}   max {d.max():.4f}")
print(f"  fraction differing by more than 0.01: {(d > 0.01).mean():.4f}")
