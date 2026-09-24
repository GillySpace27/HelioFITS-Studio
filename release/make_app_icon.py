#!/usr/bin/env python3
"""Build the HelioFITS Studio app icon: the sun-pie iris, lettered HFS.

Six wedges of the Sun at six wavelengths around a dark hexagon: an aperture made of solar images,
the mark for an application that composites them. It was this application's icon (lettered HFS)
until 2026-09-22, went to the HelioFITS plugin for a day, and came back; HelioFITS kept its AIA 171
Sun. Lettered HFS, its technical name, at 128 px and above; plain below, where letters only smudge.

iris_plain_1024.png is the full-square art, letters already removed. It was produced by the
HelioFITS repo's tools/make_app_icon.py (commit 773a575 there), which repaints the hexagon fill
and scales the orb to cover macOS's squircle. The plain art is what 64 px and below get: the six
wedges still read as a rosette at 16 px.

  python3 make_app_icon.py   ->  HFStudio_icon.icns, AppIcon.appiconset/,
                                  HFStudio_icon_1024.png, resources/images/HFStudio_icon_512.png
"""
import json, math, os, shutil, subprocess, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

S = 1024
BODY = 824 / 1024
N = 4.6
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else HERE


def squircle(size=S, frac=BODY, n=N, ss=4):
    big = size * ss
    m = Image.new("L", (big, big), 0)
    d = ImageDraw.Draw(m)
    c, h = big / 2, big * frac / 2
    pts = []
    for i in range(4800):
        t = 2 * math.pi * i / 4800
        ct, st = math.cos(t), math.sin(t)
        pts.append((c + h * math.copysign(abs(ct) ** (2 / n), ct),
                    c + h * math.copysign(abs(st) ** (2 / n), st)))
    d.polygon(pts, fill=255)
    return m.resize((size, size), Image.BOX)   # area coverage: LANCZOS would ring


def masked(img):
    """The squircle the .icns needs; the asset catalog gets unmasked() instead."""
    img = img.convert("RGBA")
    img.putalpha(squircle())
    px = np.array(img)
    px[px[..., 3] == 0, :3] = 0    # no colour under full transparency
    return Image.fromarray(px)


PLAIN = Image.open(os.path.join(HERE, "iris_plain_1024.png"))

# HFS in the hexagon, as large as it allows: at the letters' top and bottom the hexagon is narrower
# than across its middle, so the width check is made there. Hexagon measured on iris_plain_1024.png:
# centre 511.5, flat top and bottom, fill reaching 199 px from the centre.
HEX_C, HEX_APOTHEM, FONT, TEXT = 511.5, 199, "/System/Library/Fonts/Supplemental/Arial Black.ttf", "HFS"


def lettered(img):
    img = img.convert("RGBA").copy()
    size = 400
    while True:
        font = ImageFont.truetype(FONT, size)
        hb = font.getbbox("H")
        cap = hb[3] - hb[1]
        x0, _, x1, _ = font.getbbox(TEXT)
        half = HEX_APOTHEM / math.cos(math.radians(30)) - (cap / 2) / math.tan(math.radians(60))
        if x1 - x0 <= 0.80 * 2 * half:
            break
        size -= 2
    ImageDraw.Draw(img).text((HEX_C - (x0 + x1) / 2, HEX_C - (hb[1] + hb[3]) / 2), TEXT, font=font,
                             fill=(255, 255, 255, 255))
    return img


# Lettered at 128 and above; plain at 64 and below, where HFS cannot be read and only smudges.
FULL = MID = masked(lettered(PLAIN))
SMALL = masked(PLAIN)

# (pixels, artwork): what macOS will actually draw at each size it asks for.
BY_SIZE = {1024: FULL, 512: FULL, 256: FULL, 128: MID, 64: SMALL, 32: SMALL, 16: SMALL}
ICONSET = [("icon_16x16", 16), ("icon_16x16@2x", 32), ("icon_32x32", 32), ("icon_32x32@2x", 64),
           ("icon_128x128", 128), ("icon_128x128@2x", 256), ("icon_256x256", 256),
           ("icon_256x256@2x", 512), ("icon_512x512", 512), ("icon_512x512@2x", 1024)]


# The same ten images again as an asset catalog source. macOS 26 shrinks a bundle whose icon is
# only a .icns onto a grey plate at 16 and 32 pixels, whatever the artwork does: measured across
# the applications on this machine, every .icns-only one is plated there and every one with a
# compiled catalog fills the frame. deploy_release.sh compiles this with actool.
#
# These are the unmasked squares: see unmasked() for why the catalog must not be given the
# squircle that the .icns carries.
APPICONSET = [("16x16", "1x", 16), ("16x16", "2x", 32), ("32x32", "1x", 32), ("32x32", "2x", 64),
              ("128x128", "1x", 128), ("128x128", "2x", 256), ("256x256", "1x", 256),
              ("256x256", "2x", 512), ("512x512", "1x", 512), ("512x512", "2x", 1024)]


def small_first(px, img=None):
    """BOX below 64: area coverage keeps the edge crisp where LANCZOS would ring and soften it."""
    return (img or BY_SIZE[px]).resize((px, px), Image.BOX if px <= 32 else Image.LANCZOS)


def unmasked(img):
    """The art with the squircle taken off, filling its whole square canvas.

    The catalog gets this rather than the masked art, and macOS applies its own shape. Handing it
    art that is already a squircle makes it inset that inside its own, which is the grey plate at
    1024 (the size Finder asks for at its largest icon view on a 2x display). The .icns keeps the
    masked art: nothing shapes that one."""
    a = np.array(img)
    return Image.fromarray(np.dstack([a[..., :3], np.full(a.shape[:2], 255, np.uint8)]), "RGBA")


def main():
    os.makedirs(OUT, exist_ok=True)
    iconset = os.path.join(OUT, ".HFStudio.iconset")
    os.makedirs(iconset, exist_ok=True)
    for name, px in ICONSET:
        small_first(px).save(f"{iconset}/{name}.png")
    icns = os.path.join(OUT, "HFStudio_icon.icns")
    subprocess.run(["iconutil", "-c", "icns", iconset, "-o", icns], check=True)
    shutil.rmtree(iconset)

    aset = os.path.join(OUT, "AppIcon.appiconset")
    shutil.rmtree(aset, ignore_errors=True)
    os.makedirs(aset)
    images = []
    for size, scale, px in APPICONSET:
        fn = f"icon_{size}_{scale}.png"
        small_first(px, unmasked(BY_SIZE[px])).save(os.path.join(aset, fn))
        images.append({"idiom": "mac", "size": size, "scale": scale, "filename": fn})
    with open(os.path.join(aset, "Contents.json"), "w") as f:
        json.dump({"images": images, "info": {"version": 1, "author": "make_app_icon.py"}}, f, indent=2)
        f.write("\n")

    # Windows reads a .ico, which holds one image per size exactly as the .icns does, so the small
    # sizes there get the small artwork too rather than a shrunken copy of the large one.
    win = {16: SMALL, 32: SMALL, 48: SMALL, 64: SMALL, 128: MID, 256: FULL}
    # Largest first: Pillow writes the file from that one and skips any size bigger than it.
    imgs = [small_first(px, art) for px, art in sorted(win.items(), reverse=True)]
    ico = os.path.join(OUT, "HFStudio_icon.ico")
    imgs[0].save(ico, format="ICO", sizes=[(px, px) for px in sorted(win)], append_images=imgs[1:])

    FULL.save(os.path.join(OUT, "HFStudio_icon_1024.png"))
    BY_SIZE[512].resize((512, 512), Image.LANCZOS).save(
        os.path.join(OUT, "..", "resources", "images", "HFStudio_icon_512.png"))
    print(f"wrote {icns}, {ico}, {aset}/ and resources/images/HFStudio_icon_512.png")


if __name__ == "__main__":
    main()
