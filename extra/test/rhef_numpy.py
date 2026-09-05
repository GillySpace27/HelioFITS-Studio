"""A vectorised NumPy RHEF using the histogram formulation, to separate the algorithm's
contribution from the language's.

Same annuli as the Java filter (linear plane-of-sky radius, 1 pixel wide), same input array,
same rank definition (average rank within ties, normalised to [0, 1] over n-1).
"""
import time, numpy as np

W = 4096
data = np.fromfile("punch_normalised.f32", dtype=np.float32).reshape(W, W)
java = np.fromfile("punch_rhef_java.f32", dtype=np.float32).reshape(W, W)

rsun_px = 955.9741092736674 / 3600 / 0.0225
half = 2048 / rsun_px
pix = 2 * half / W
y, x = np.mgrid[0:W, 0:W]
r = np.hypot(-half + (x + .5) * pix, -half + (y + .5) * pix)
annulus = np.floor(r / pix).astype(np.int32).ravel()

flat = data.ravel()
codes = flat.astype(np.float16).view(np.uint16).astype(np.int64)   # the half-float bit pattern
positive = flat > 0

t = time.perf_counter()
nA = annulus.max() + 1
NC = 1 << 16
key = annulus.astype(np.int64) * NC + codes                        # (annulus, value) pair
key_pos = key[positive]
counts = np.bincount(key_pos, minlength=nA * NC)                   # the histograms, all at once
cum = np.cumsum(counts)                                            # running total across all bins
per_annulus = np.bincount(annulus[positive], minlength=nA)
starts = np.concatenate(([0], np.cumsum(per_annulus)[:-1]))        # where each annulus begins
# rank of a value = (pixels below it in its annulus) + (its own run - 1)/2, over n - 1
# cum and counts are per (annulus, code) bin; index them by each pixel's own bin
below_bin = cum - counts                      # pixels before this bin, globally
below = below_bin[key_pos] - starts[annulus[positive]]   # ... and within its annulus
rank = (below + (counts[key_pos] - 1) / 2) / np.maximum(per_annulus[annulus[positive]] - 1, 1)
out = flat.copy()
out[positive] = rank.astype(np.float32)
out[per_annulus[annulus] < 5] = flat[per_annulus[annulus] < 5]
dt = time.perf_counter() - t

print(f"numpy histogram RHEF: {dt * 1000:.1f} ms")
m = positive & (per_annulus[annulus] >= 5)
d = np.abs(out[m] - java.ravel()[m])
print(f"  vs java: max |difference| {d.max():.2e}, pixels differing by >1e-4: {(d > 1e-4).sum()} of {m.sum()}")
