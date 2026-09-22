#!/usr/bin/env python3
"""Build the HFS iris mark: the sun-pie as full-bleed squircle art, reading HFS.

This was the application's icon while it was called HFStudio. It is kept because the mark is
going to the HelioFITS preview plugin, replacing the AIA 171 image it uses now. The application
itself is drawn by make_punch_icon.py.

macOS 26 enforces the squircle on app-bundle icons: an icon that does not fill it is shrunk onto
a grey squircle ("squircle jail"). The previous icon met that by setting the round mark on a black
squircle tile, which read as an orb in a box. This one fills the squircle with the mark itself:
the orb is scaled until its disk covers the squircle, so the six wavelength blades run straight
out to the edges and into the corners, and the squircle clips it. Nothing is warped, so the blade
edges stay straight. The outline is the same 824-of-1024 body and exponent as before, which is
the shape already seen to escape the jail (issue #7).

The hexagon's "HF" becomes "HFS": the old letters are inpainted away, so the hexagon's faint
radial gradient fills back in, and HFS is set in the same heavy grotesque.

  python3 make_heliofits_iris.py <orb.png>  ->  heliofits_iris_1024.png + heliofits_iris.icns

The orb is the bare 1024 px circular mark, deleted with release/install4j. Recover it first:

  git show "$(git log --all --format=%h -1 -- release/install4j/resources/HFS_icon.png)^:release/install4j/resources/HFS_icon.png" > /tmp/HFS_icon.png
  python3 make_squircle_icon.py /tmp/HFS_icon.png

Do not pass heliofits_iris_1024.png: that is this script's own output.
"""
import math, subprocess, sys, os
from PIL import Image, ImageChops, ImageDraw, ImageFont
import numpy as np
import cv2

if len(sys.argv) != 2:
    sys.exit(__doc__)

HERE = os.path.dirname(os.path.abspath(__file__))
S = 1024
BODY = 824 / 1024   # Apple's icon grid: the body is 824 of a 1024 canvas
N = 4.6             # squircle exponent, unchanged from the icon that escaped the jail
SS = 4              # supersampling for an antialiased edge

# The orb's hexagon, measured by marching 720 rays from the centre and fitting a regular hexagon
# (median error 2 px): flat top and bottom, a vertex pointing along +x, the dark fill reaching
# 200 px from the centre and its black rim 209. ponytail: constants for the one orb there is;
# refit if the orb is ever redrawn.
FILL_APOTHEM = 200
FONT = "/System/Library/Fonts/Supplemental/Arial Black.ttf"   # the heavy grotesque of the old HF
TEXT = "HFS"


def squircle(size, frac=BODY, n=N):
    """The body mask, drawn at SS times the size and reduced, so its edge is antialiased."""
    big = size * SS
    m = Image.new("L", (big, big), 0); d = ImageDraw.Draw(m)
    c = big / 2; h = big * frac / 2; p = []
    for i in range(4800):
        t = 2 * math.pi * i / 4800; ct, st = math.cos(t), math.sin(t)
        p.append((c + h * math.copysign(abs(ct) ** (2 / n), ct),
                  c + h * math.copysign(abs(st) ** (2 / n), st)))
    d.polygon(p, fill=255)
    return m.resize((size, size), Image.BOX)   # area coverage: LANCZOS would ring, faint alpha off the edge


def hexagon(cx, cy, apothem):
    r = apothem / math.cos(math.radians(30))
    return [(cx + r * math.cos(math.radians(60 * k)), cy + r * math.sin(math.radians(60 * k))) for k in range(6)]


raw = Image.open(sys.argv[1]).convert("RGBA")
a = np.array(raw.split()[3]); ys, xs = np.nonzero(a > 8)
cx, cy = (xs.min() + xs.max()) / 2, (ys.min() + ys.max()) / 2
radius = (xs.max() - xs.min() + 1) / 2

# 1. Take the old letters out of the hexagon. Its fill is a faint radial gradient, so repaint the
#    whole fill from a gradient fitted to the pixels well clear of the letters: inpainting the
#    letters alone left their ghosts, visible on a light background.
rgba = np.array(raw)
f = Image.new("L", raw.size, 0); ImageDraw.Draw(f).polygon(hexagon(cx, cy, FILL_APOTHEM - 1), fill=255); fill = np.array(f) > 0
lum = rgba[..., :3].astype(np.float32) @ np.array([0.299, 0.587, 0.114], np.float32)
letters = cv2.dilate((fill & (lum > 30)).astype(np.uint8), np.ones((31, 31), np.uint8)) > 0
yy, xx = np.mgrid[:raw.size[1], :raw.size[0]]; r = np.hypot(xx - cx, yy - cy)
clear = fill & ~letters
for ch in range(3):
    rgba[..., ch][fill] = np.clip(np.polyval(np.polyfit(r[clear], rgba[..., ch][clear].astype(float), 2), r[fill]), 0, 255).round()
orb = Image.fromarray(rgba)

# 2. Set HFS as large as the hexagon allows: at the letters' top and bottom edges the hexagon is
#    narrower than across its middle, so the width check is made there, with a margin.
draw = ImageDraw.Draw(orb)
size = 400
while True:
    font = ImageFont.truetype(FONT, size)
    cap = font.getbbox("H")[3] - font.getbbox("H")[1]
    x0, y0, x1, y1 = font.getbbox(TEXT)
    half_width_at_cap = FILL_APOTHEM / math.cos(math.radians(30)) - (cap / 2) / math.tan(math.radians(60))
    if x1 - x0 <= 0.80 * 2 * half_width_at_cap:
        break
    size -= 2
hb = font.getbbox("H")
draw.text((cx - (x0 + x1) / 2, cy - (hb[1] + hb[3]) / 2), TEXT, font=font, fill=(255, 255, 255, 255))
print(f"HFS set at {size} pt: cap height {cap} px, width {x1 - x0} px, in a hexagon {2 * FILL_APOTHEM} px tall")

# 3. Scale the orb until its disk covers the squircle, corners included, and clip.
cover = S * BODY / 2 * 2 ** (0.5 - 1 / N) + 4   # the squircle's corner radius, plus a margin
k = cover / radius
side = round(raw.size[0] * k)
big = orb.resize((side, side), Image.LANCZOS)
# Black behind the orb: the gaps between its blades are transparent in the source, and it was
# the old black tile that made them read as black lines. Without it they are holes in the icon.
placed = Image.new("RGBA", (S, S), (0, 0, 0, 0))
placed.paste(big, (round(S / 2 - cx * k), round(S / 2 - cy * k)))
canvas = Image.alpha_composite(Image.new("RGBA", (S, S), (0, 0, 0, 255)), placed)
icon = canvas.copy()
icon.putalpha(ImageChops.multiply(canvas.split()[3], squircle(S)))
px = np.array(icon); px[px[..., 3] == 0, :3] = 0   # no colour left under full transparency: a viewer
icon = Image.fromarray(px)                          # that ignores alpha would otherwise show the disk
print(f"orb scaled {k:.3f}x to a {2 * cover:.0f} px disk under an {S * BODY:.0f} px squircle")

icon.save(os.path.join(HERE, "heliofits_iris_1024.png"))

iconset = os.path.join(HERE, ".heliofits_iris.iconset"); os.makedirs(iconset, exist_ok=True)
for base in (16, 32, 128, 256, 512):
    icon.resize((base, base), Image.LANCZOS).save(f"{iconset}/icon_{base}x{base}.png")
    icon.resize((base * 2, base * 2), Image.LANCZOS).save(f"{iconset}/icon_{base}x{base}@2x.png")
subprocess.run(["iconutil", "-c", "icns", iconset, "-o", os.path.join(HERE, "heliofits_iris.icns")], check=True)
subprocess.run(["rm", "-rf", iconset])
print("wrote heliofits_iris_1024.png + heliofits_iris.icns")
