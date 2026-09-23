#!/usr/bin/env python3
"""Build the HelioFITS Studio app icon: nested instrument fields, drawn three times for three size bands.

The picture is the ladder this application composites: the occulted Sun, then each instrument's
field of view as a ring around it, fainter as it goes out, with the corona running through all of
them. Nothing is lettered, so it needs no translation and no explaining at 16 pixels.

An icon is not one image. The .icns carries ten, and the same artwork cannot serve 1024 and 16:
four rings and a ray texture average into one grey square when they land inside 16 pixels. So
three artworks are drawn and assigned by how many pixels they will actually have:

  1024, 512, 256   full     four fields, ray texture, thin bright field edges
  128, 64          mid      three fields, dark gaps between them, softer texture
  32, 16           small    two fields, no texture, large bright core

Nobody ever sees two of them at once. macOS picks by size, and so does Windows' .ico.

The body is 824 of 1024 at exponent 4.6, the shape already seen to escape macOS 26's squircle
jail (issue #7), and the art fills it rather than sitting on a tile.

  python3 make_app_icon.py   ->  HFStudio_icon.icns, AppIcon.appiconset/,
                                  HFStudio_icon_1024.png, resources/images/HFStudio_icon_512.png
"""
import json, math, os, shutil, subprocess, sys
import cv2
import numpy as np
from PIL import Image, ImageDraw

S = 1024
BODY = 824 / 1024
N = 4.6
OUT = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))

yy, xx = np.mgrid[0:S, 0:S].astype(np.float32)
cx = cy = (S - 1) / 2
R = np.hypot(xx - cx, yy - cy) / (S / 2)
TH = np.arctan2(-(yy - cy), xx - cx)
TN, RN = 1440, 320
_ti = (((TH + math.pi) / (2 * math.pi)) * TN).astype(np.int32) % TN
_ri = np.clip((R / 1.5) * RN, 0, RN - 1).astype(np.int32)

SPACE = np.array([6, 9, 20], np.float32)
DISK = np.array([8, 10, 18], np.float32)
PHOTOSPHERE = np.array([238, 242, 250], np.float32)


def rays(angular=6.0, radial=55.0, seed=13):
    """Noise coherent along each radius: rays, not static. Exponentiated, so it never goes dark."""
    rng = np.random.default_rng(seed)
    pol = rng.random((TN, RN)).astype(np.float32)
    pol = np.vstack([pol[-TN // 4:], pol, pol[:TN // 4]])          # pad so the seam wraps
    sm = cv2.GaussianBlur(pol, (0, 0), sigmaX=radial, sigmaY=angular, borderType=cv2.BORDER_REFLECT)
    sm = sm[TN // 4:TN // 4 + TN]
    return ((sm - sm.mean()) / (sm.std() + 1e-6))[_ti, _ri]


def streamers(spec):
    """A few streamers, wider further out, as the real ones are."""
    out = np.zeros_like(TH)
    for a, w, amp in spec:
        d = np.abs(np.mod(TH - math.radians(a) + math.pi, 2 * math.pi) - math.pi)
        out += amp * np.exp(-(d / (w * (1 + 0.45 * R))) ** 2)
    return out


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


MASK = squircle()
ST = 0.55 + 0.9 * streamers([(8, 0.30, 1.0), (188, 0.32, 0.9)])


def art(rings, tex=0.0, edges=None, gap=0.0, core=0.0, occ=0.17):
    r = np.maximum(R, 1e-3)
    rgb = np.zeros((S, S, 3), np.float32) + SPACE
    texture = np.exp(tex * rays()) if tex else 1.0
    for r0, r1, tint, amp in rings:
        band = ((r >= r0) & (r < r1)).astype(np.float32)
        fade = np.clip((r1 - r) / (r1 - r0), 0, 1) ** 0.6
        rgb += (band * fade * amp * texture * ST)[..., None] * np.array(tint, np.float32)
        if edges == "bright":
            rgb[np.abs(r - r1) < 0.005] = np.array(tint, np.float32) * 0.85
        elif edges == "gap":
            rgb[np.abs(r - r1) < gap] = DISK
    if core:
        rgb += (np.exp(-((r - occ) / 0.09) ** 2) * core)[..., None] * np.array([255, 226, 170], np.float32)
    rgb[R <= occ] = DISK
    rgb[np.abs(R - occ * 0.58) < 0.0055] = PHOTOSPHERE    # the photosphere, as a coronagraph marks it
    img = Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), "RGB").convert("RGBA")
    img.putalpha(MASK)
    px = np.array(img)
    px[px[..., 3] == 0, :3] = 0    # no colour under full transparency
    return Image.fromarray(px)


FULL = art([(0.17, 0.30, (255, 214, 150), 1.00), (0.30, 0.52, (150, 190, 255), 0.66),
            (0.52, 0.78, (126, 226, 216), 0.44), (0.78, 1.45, (196, 176, 255), 0.30)],
           tex=0.34, edges="bright")

MID = art([(0.20, 0.42, (255, 220, 160), 1.15), (0.44, 0.74, (138, 184, 255), 0.66),
           (0.76, 1.45, (124, 218, 212), 0.34)],
          tex=0.30, edges="gap", gap=0.012, occ=0.20)

SMALL = art([(0.24, 0.56, (255, 214, 148), 1.35), (0.56, 1.45, (126, 182, 250), 0.52)],
            core=0.34, occ=0.24)

# (pixels, artwork): what macOS will actually draw at each size it asks for.
BY_SIZE = {1024: FULL, 512: FULL, 256: FULL, 128: MID, 64: MID, 32: SMALL, 16: SMALL}
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
    win = {16: SMALL, 32: SMALL, 48: MID, 64: MID, 128: MID, 256: FULL}
    # Largest first: Pillow writes the file from that one and skips any size bigger than it.
    imgs = [small_first(px, art) for px, art in sorted(win.items(), reverse=True)]
    ico = os.path.join(OUT, "HFStudio_icon.ico")
    imgs[0].save(ico, format="ICO", sizes=[(px, px) for px in sorted(win)], append_images=imgs[1:])

    FULL.save(os.path.join(OUT, "HFStudio_icon_1024.png"))
    BY_SIZE[512].resize((512, 512), Image.LANCZOS).save(
        os.path.join(OUT, "..", "resources", "images", "HFStudio_icon_512.png"))
    print(f"wrote {icns}, {ico}, {aset}/ and resources/images/HFStudio_icon_512.png")
    print("  full art  1024, 512, 256")
    print("  mid art   128, 64")
    print("  small art 32, 16")


if __name__ == "__main__":
    main()
