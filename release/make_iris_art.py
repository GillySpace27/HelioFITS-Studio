#!/usr/bin/env python3
"""Draw iris_leaf_plain_1024.png: the sun-pie iris with white dividers and a full blue leaf.

The iris is six SDO/AIA channels of the 2024-05-14 X8.7 flare (304, 1600, 171, 211, 131, 335)
around a dark hexagon. iris_plain_1024.png is that art with black dividers, the red 304 leaf
running across the whole top and the blue 335 leaf a small triangle under it. This script makes
the family version (2026-10-23, Gilly's pick beside Peek, Heliad and Heliogram):

  * the blue leaf is a full 60 degree iris leaf, its point at the hexagon's right corner and its
    sides running on to the Sun's edge, so the red leaf keeps only the top left;
  * the blue leaf and the whole cyan leaf are painted from the real AIA 335 and 131 frames in
    iris_sources/ (see SOURCES.md there), not from the old art. The old cyan was too noisy to
    extend without a seam, so it is replaced whole;
  * the dividers are Heliogram's chrome white (#E7EAF3), 22 px at 1024, with round joints.

The red, olive, gold and purple leaves are the original art, untouched.

Co-alignment: every leaf of iris_plain_1024.png is its channel's full-disc frame with the limb on
the orb's edge. Orb pixel p maps to frame pixel 600 + k (p - 511.5) + t. Fitted by brute-force
normalized cross-correlation of luminance on a patch inside each leaf (computed 2026-10-23 with
the search kept here as fit()): blue k 0.977, t (5, -4), NCC 0.99; cyan k 1.000, t (0, -1),
NCC 0.80; red and olive k 1.000, t (-2, -2) and (-2, -1), NCC 0.96 and 0.92.

Colour: the frames are Helioviewer renders, brighter and flatter than the original art. Each
new leaf's colour is looked up from the frame's brightness by a table fitted where the frame and
the original leaf overlap (the median original colour at each brightness).

  python3 make_iris_art.py          ->  iris_leaf_plain_1024.png
  python3 make_iris_art.py --fit    prints the co-alignment search for the blue and cyan leaves
Needs numpy and Pillow. make_app_icon.py letters the result.
"""
import itertools, math, os, sys
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "iris_sources")
ORIGINAL = os.path.join(HERE, "iris_plain_1024.png")
OUT = os.path.join(HERE, "iris_leaf_plain_1024.png")

CHROME = (0xE7, 0xEA, 0xF3)
LINE = 22                                 # divider width at 1024
C, APO = 511.5, 202                       # hexagon centre and apothem, to the middle of the old dividers
R = APO / math.cos(math.radians(30))
V = {"L": (C - R, C), "R": (C + R, C),
     "TL": (C - R / 2, C - APO), "TR": (C + R / 2, C - APO),
     "BL": (C - R / 2, C + APO), "BR": (C + R / 2, C + APO)}
ORB = 497                                 # the orb's radius: the dividers stop at its edge

# Where each new leaf's original sits on its frame (fitted, see the docstring), and the patch fitted on.
FIT = {"blue": ("335", (0.977, 5.0, -4.0), [(665, 335), (820, 335), (745, 470)]),
       "cyan": ("131", (1.000, 0.0, -1.0), [(870, 360), (970, 360), (990, 600), (950, 690), (720, 690)])}
COLOUR_PATCH = {"blue": [(650, 330), (838, 330), (745, 482)],
                "cyan": [(880, 335), (975, 335), (985, 560), (900, 560)]}
RENDER = (1.0, -2.0, -2.0)                # drawn where the red and olive leaves sit: limb on the orb's edge


def poly(points, n=1024, ss=4):
    """Area coverage of a polygon, 0..1."""
    m = Image.new("L", (n * ss, n * ss), 0)
    ImageDraw.Draw(m).polygon([(x * ss, y * ss) for x, y in points], fill=255)
    return np.array(m.resize((n, n), Image.BOX)).astype(np.float32) / 255


def frame(ch, k, tx, ty, mode="RGB"):
    """The channel's 1200 px frame resampled onto the 1024 orb grid."""
    im = Image.open(os.path.join(SRC, f"aia_{ch}.png")).convert(mode)
    c = 600 - k * 511.5
    return np.array(im.transform((1024, 1024), Image.AFFINE, (k, 0, c + tx, 0, k, c + ty),
                                 Image.BICUBIC)).astype(np.float32)


def colour_table(src, dst, bins=256):
    """Median original colour at each frame brightness, gaps interpolated, lightly smoothed."""
    idx = np.clip((src.sum(1) / 3).astype(int), 0, bins - 1)
    table = np.zeros((bins, 3))
    good = [b for b in range(bins) if (idx == b).sum() >= 8]
    for b in good:
        table[b] = np.median(dst[idx == b], 0)
    for c in range(3):
        v = np.interp(np.arange(bins), good, table[good, c])
        table[:, c] = np.convolve(np.pad(v, 3, mode="edge"), np.ones(7) / 7, mode="valid")
    return table


def look_up(table, img):
    s = img.sum(2) / 3
    i0 = np.clip(np.floor(s).astype(int), 0, 255)
    f = (s - i0)[..., None]
    return table[i0] * (1 - f) + table[np.clip(i0 + 1, 0, 255)] * f


def leaves():
    """The full blue leaf (point at R, sides through TR and its mirror) and the cyan leaf beside it."""
    up = (V["TR"][0] - V["R"][0], V["TR"][1] - V["R"][1])
    A = (V["R"][0] + 3 * up[0], V["R"][1] + 3 * up[1])
    B = (V["R"][0] - 3 * up[0], V["R"][1] + 3 * up[1])
    blue = poly([V["R"], A, B])
    cyan = np.clip(poly([V["R"], B, (1300, B[1]), (1300, C + APO), V["BR"]]) - blue, 0, 1)
    return blue, cyan, A, B


def art():
    orig = np.array(Image.open(ORIGINAL).convert("RGB")).astype(np.float32)
    blue, cyan, A, B = leaves()
    yy, xx = np.mgrid[0:1024, 0:1024]
    inside = np.clip((ORB + 2 - np.hypot(xx - C, yy - C)) / 3, 0, 1)[..., None]
    out = orig.copy()
    for leaf, region in (("blue", blue), ("cyan", cyan)):
        ch, fit_at, _ = FIT[leaf]
        m = poly(COLOUR_PATCH[leaf]) > 0.999
        table = colour_table(frame(ch, *fit_at)[m], orig[m])
        new = look_up(table, frame(ch, *RENDER)) * inside
        out = out * (1 - region[..., None]) + new * region[..., None]
    img = Image.fromarray(np.clip(out, 0, 255).astype(np.uint8))
    return dividers(img, A, B)


def dividers(img, A, B, ss=4):
    segs = [(V["TL"], V["TR"]), (V["BL"], V["BR"]),
            (V["TR"], V["R"]), (V["R"], V["BR"]), (V["BL"], V["L"]), (V["L"], V["TL"]),
            (V["TL"], (-300, C - APO)), (V["BL"], (1324, C + APO)),
            (V["L"], (V["L"][0] + 2 * (V["L"][0] - V["TL"][0]), V["L"][1] + 2 * (V["L"][1] - V["TL"][1]))),
            (V["BL"], (V["BL"][0] + 2 * (V["BL"][0] - V["L"][0]), V["BL"][1] + 2 * (V["BL"][1] - V["L"][1]))),
            (V["TR"], A), (V["R"], B)]          # the blue leaf's two sides
    big = Image.new("L", (1024 * ss, 1024 * ss), 0)
    d = ImageDraw.Draw(big)
    for p, q in segs:
        d.line([(p[0] * ss, p[1] * ss), (q[0] * ss, q[1] * ss)], fill=255, width=LINE * ss)
    for v in V.values():                                  # round joints
        d.ellipse([(v[0] - LINE / 2) * ss, (v[1] - LINE / 2) * ss, (v[0] + LINE / 2) * ss, (v[1] + LINE / 2) * ss], fill=255)
    clip = Image.new("L", big.size, 0)
    ImageDraw.Draw(clip).ellipse([(512 - ORB) * ss, (512 - ORB) * ss, (512 + ORB) * ss, (512 + ORB) * ss], fill=255)
    mask = Image.fromarray(np.minimum(np.array(big), np.array(clip))).resize((1024, 1024), Image.BOX)
    img.paste(Image.new("RGB", (1024, 1024), CHROME), (0, 0), mask)
    return img


def fit(leaf):
    """Brute-force co-alignment of one leaf: coarse to fine over k, tx, ty, by NCC of luminance."""
    ch, _, patch = FIT[leaf]
    orig = np.array(Image.open(ORIGINAL).convert("L")).astype(np.float32)
    aia = Image.open(os.path.join(SRC, f"aia_{ch}.png")).convert("L")
    m = poly(patch) > 0.999

    def score(ds, k, tx, ty):
        n = 1024 // ds
        o = np.array(Image.fromarray(orig).resize((n, n), Image.BOX))
        mm = np.array(Image.fromarray(m.astype(np.uint8) * 255).resize((n, n), Image.BOX)) > 250
        c = 600 + k * (ds / 2 - 0.5 - 511.5)
        w = np.array(aia.transform((n, n), Image.AFFINE, (k * ds, 0, c + tx, 0, k * ds, c + ty), Image.BILINEAR)).astype(np.float32)
        a, b = o[mm] - o[mm].mean(), w[mm] - w[mm].mean()
        return float((a * b).sum() / np.sqrt((a * a).sum() * (b * b).sum() + 1e-9))

    k, tx, ty = 1.0, 0.0, 0.0
    for ds, ks, ts in ((8, np.arange(-0.5, 0.61, 0.05), np.arange(-100, 101, 10)),
                       (4, np.arange(-0.06, 0.061, 0.015), np.arange(-12, 13, 3)),
                       (2, np.arange(-0.012, 0.0121, 0.004), np.arange(-3, 3.1, 1))):
        best = max((score(ds, k + dk, tx + dx, ty + dy), k + dk, tx + dx, ty + dy)
                   for dk, dx, dy in itertools.product(ks, ts, ts))
        r, k, tx, ty = best
    return r, k, tx, ty


if __name__ == "__main__":
    if "--fit" in sys.argv:
        for leaf in FIT:
            r, k, tx, ty = fit(leaf)
            print(f"{leaf}: AIA {FIT[leaf][0]}  k {k:.3f}  t ({tx:.0f}, {ty:.0f})  NCC {r:.3f}")
    else:
        art().save(OUT)
        print(f"wrote {OUT}")
