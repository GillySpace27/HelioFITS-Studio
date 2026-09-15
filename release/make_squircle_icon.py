#!/usr/bin/env python3
"""Build the Tahoe-compliant squircle app icon from the HelioFITS Studio sun-pie mark.

macOS 26 enforces the squircle on app-bundle icons: a bare circular icon is shrunk
onto a grey squircle ("squircle jail"). This composes the mark, unmodified, onto a
black squircle tile, so the bundled .app reads as a native icon while keeping the brand.

  python3 make_squircle_icon.py <mark.png>  ->  HFStudio_icon_squircle_1024.png + HFStudio_icon_squircle.icns
  and resources/images/HFStudio_icon_512.png, the same tile for the jar's Dock, window and About icon

The mark is the bare 1024 px circular orb. It used to live at
release/install4j/resources/HFS_icon.png, which was deleted in e8d52ff4c, so recover it first:

  git show e8d52ff4c^:release/install4j/resources/HFS_icon.png > /tmp/HFS_icon.png
  python3 make_squircle_icon.py /tmp/HFS_icon.png

Do not pass resources/images/HFStudio_icon_512.png: that is this script's own output, so it
would end up boxed inside a second tile.
"""
import math, subprocess, sys, os
from PIL import Image, ImageDraw, ImageFilter
import numpy as np

if len(sys.argv) != 2:
    sys.exit(__doc__)

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = sys.argv[1]
S = 1024

BODY = 824 / 1024   # Apple's icon grid: the body is 824 of a 1024 canvas; the rest is shadow room

def squircle(size, n=4.6, frac=BODY):
    m = Image.new("L", (size, size), 0); d = ImageDraw.Draw(m)
    c = size / 2; h = size * frac / 2; p = []
    for i in range(2400):
        t = 2 * math.pi * i / 2400; ct, st = math.cos(t), math.sin(t)
        p.append((c + h * math.copysign(abs(ct) ** (2 / n), ct),
                  c + h * math.copysign(abs(st) ** (2 / n), st)))
    d.polygon(p, fill=255); return m

raw = Image.open(SRC).convert("RGBA")
a = np.array(raw.split()[3])
ys, xs = np.nonzero(a > 8)
orb = raw.crop((xs.min(), ys.min(), xs.max() + 1, ys.max() + 1))   # tight-crop: the source has padding

sq = squircle(S)
icon = Image.new("RGBA", (S, S), (0, 0, 0, 0))
icon.paste(Image.new("RGBA", (S, S), (0, 0, 0, 255)), (0, 0), sq)   # black tile
INSET = 0.94                                    # orb inscribed in the body, minimal margin
n = int(S * BODY * INSET)
o = orb.resize((n, n), Image.LANCZOS)
icon.paste(o, ((S - n) // 2, (S - n) // 2), o)
icon = Image.composite(icon, Image.new("RGBA", (S, S), (0, 0, 0, 0)), sq)
icon.save(os.path.join(HERE, "HFStudio_icon_squircle_1024.png"))
icon.resize((512, 512), Image.LANCZOS).save(os.path.join(HERE, "..", "resources", "images", "HFStudio_icon_512.png"))

iconset = os.path.join(HERE, ".HFStudio.iconset"); os.makedirs(iconset, exist_ok=True)
for base in (16, 32, 128, 256, 512):
    icon.resize((base, base), Image.LANCZOS).save(f"{iconset}/icon_{base}x{base}.png")
    icon.resize((base * 2, base * 2), Image.LANCZOS).save(f"{iconset}/icon_{base}x{base}@2x.png")
subprocess.run(["iconutil", "-c", "icns", iconset, "-o", os.path.join(HERE, "HFStudio_icon_squircle.icns")], check=True)
subprocess.run(["rm", "-rf", iconset])
print("wrote HFStudio_icon_squircle_1024.png + HFStudio_icon_squircle.icns + resources/images/HFStudio_icon_512.png")
