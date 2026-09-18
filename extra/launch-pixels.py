"""Did the image actually draw? Counts sun-coloured pixels in a screenshot of the app.

    python extra/launch-pixels.py <screenshot.png> <minimum>

The test image is a synthetic AIA 171 disk, drawn in the amber AIA 171 colour table, so a drawn
frame is thousands of warm pixels and an undrawn canvas is black. The application's own chrome is
purple and grey and contributes almost none: measured on the first Linux run, 162 warm pixels
outside the canvas against 8529 inside it. That margin is why a plain count is enough, provided
the screenshot holds the app window and not a desktop behind it (Windows wallpaper alone came to
6592, which is why launch.yml captures only the window there).
"""
import sys

from PIL import Image

path, minimum = sys.argv[1], int(sys.argv[2])
im = Image.open(path).convert("RGB")
warm = sum(1 for r, g, b in im.getdata() if r > 80 and r > b + 30 and g > 40)
print(f"{path}: {im.size[0]}x{im.size[1]}, {warm} sun-coloured pixels (need {minimum})")
if warm < minimum:
    print("FAIL the image did not draw, or drew too small to see")
    sys.exit(1)
print("PASS the image drew")
