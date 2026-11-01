# iris_sources

Inputs of `make_iris_art.py`. Both are SDO/AIA full-disc images of the 2024-05-14 X8.7 flare,
rendered by Helioviewer with its standard AIA colour tables. They are byte-scaled renders for
the icon, not calibrated data.

| File | Channel | Image time (UTC) | Helioviewer sourceId |
|---|---|---|---|
| `aia_131.png` | AIA 131 | 2024-05-14 16:50:43 | 9 |
| `aia_335.png` | AIA 335 | 2024-05-14 16:50:49 | 14 |

Both were fetched on 2026-10-23 for a requested time of 2024-05-14T16:51:00Z, as 1200 x 1200 px
frames at 1.9 arcsec/px centred on the Sun, with no watermark, scale bar or events.
The image times come from `getClosestImage`. The request for each was:

```
https://api.helioviewer.org/v2/takeScreenshot/?date=2024-05-14T16:51:00Z&imageScale=1.9&layers=[SDO,AIA,AIA,<channel>,1,100]&events=&eventLabels=false&scale=false&watermark=false&x0=0&y0=0&width=1200&height=1200&display=true
```

Credit: SDO/AIA data courtesy of NASA/SDO and the AIA science team; rendered by Helioviewer
(helioviewer.org).

Why these two: `iris_plain_1024.png` co-aligns with frames of this time channel by channel (see
the `make_iris_art.py` docstring), so the leaves it lacks can be drawn from the same Sun.
