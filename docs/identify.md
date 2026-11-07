# Identify under cursor

The footer can name what is under the pointer: the Sun, the Moon or a planet, as each image
layer's own observer saw it at that layer's frame time. A label beside the pointer can say the same
after it rests. A transit index answers the reverse question: when is a body in a field of view,
for example to find a date without the Moon in PUNCH.

## Switches and settings

- **View > Identify Under Cursor** appends the name to the footer, after the coordinates and the
  pixel value. Right-click on the canvas copies the footer, name included.
- **View > Identify Tooltip** also draws the name, its distance and its angle from the Sun beside
  the pointer once it rests (Settings > Identify > Label delay). The label is never recorded.
- **View > Identify Stars** includes stars. It needs the star catalog of the starfield layer and
  names nothing until that catalog is present.
- **Settings > Identify** holds the pointer tolerance in screen pixels (default 6), the label delay
  (400 ms), the near-Earth observer uncertainty (7,400 km), light travel time (off), Pluto (on),
  and the star options (IAU names, Bayer and Flamsteed designations, faintest magnitude).

All defaults are judgements and can be changed. Settings keys start with `identify.`.

## How a name is chosen

1. The pointer becomes a sight line in each visible image layer's frame, by the same geometry
   that layer is drawn with: in HPC and Observer Sky the helioprojective angles under the pointer;
   in Helioradial and Helioradial Unrolled the plane-of-sky point, turned into angles with that
   layer's own observer distance; in Orthographic the screen point rotated into the layer's frame,
   on the solar disk where the disk is in front and on the layer's image plane elsewhere.
   Latitudinal maps the solar surface and Helioradial rendered in 3D draws a warped surface, so
   neither names anything. With no image layer the display's viewpoint is used.
2. Each body's position comes from the bundled SPICE kernels (de432s_reduced: the Sun, Moon,
   planets and the Pluto barycentre, 1989-12-30 to 2050-01-01), put in the same frame.
3. A body is named when the angle between the two is within its angular radius, plus the pointer
   tolerance measured on screen, plus, for an observer within a million km of Earth's centre, the
   near-Earth uncertainty. That last term exists because PUNCH L3 mosaics place the observer at
   Earth's centre (HGLN_OBS 0, GEOX_OBS empty) while the spacecraft are in low orbit; it is worth
   about a degree for the Moon and nothing for the planets.
4. A body that is not on the imagery says why: behind the Sun, behind the occulter, or outside the
   field (the layer's own pixels, by the WCS mapping the shaders use).

Accuracy: against sunpy 8.0.1 with the same kernel, positions agree to 0.0001 degrees for an
Earth-centred observer and an observer at 0.3 AU (`extra/test/IdentifyCheck.java`, scripts in
`extra/test/data/identify_*.py`). Light travel time moves the Moon by about 0.005 degrees from
Earth and the planets by up to about 0.01 degrees.

## Commands

| Id | Does |
|---|---|
| `identifyUnderCursor`, `identifyTooltip`, `identifyStars` | The View menu switches |
| `identifyAt` | Bodies at a helioprojective direction for an observer (the active layer's by default) |
| `identifyLocate` | Where a body is: Tx, Ty, distance, elongation, and whether the active layer holds it |
| `identifyInField` | When each body is in a field over a time range, by body and by UTC day |
| `identifySet` | Change any identifier setting; answers with all of them |

`identifyInField` takes a field of `punch` (a 46.08 degree half-width square, from CDELT 0.0225
degrees times 2048 pixels in a PTM header), `layer` (the active layer's own pixels) or `custom`
(inner and outer elongation and an optional square cut), samples every hour by default and
refines each edge to a minute. It reads the ephemeris, not the files on disk, so it answers for
dates nothing has been downloaded for.

## Calendar and timeline

The date picker's calendar can show which bodies are in a field on each day. Below the days,
**Bodies** chooses the field: Off, PUNCH field (the same square as `identifyInField punch`, seen
from Earth) or Active layer (the active layer's own pixels, its observer kept at a fixed offset
from Earth). Each day then carries one small dot per body in the field at any hour that day
(Moon white, planets in their marker colours), and hovering a day lists them. **Hide** greys out
and disables days: No days, Moon days (days the Moon is in the field) or Any body. A month is
computed in the background the first time it is shown and kept for the session; the days look
plain until it is ready.

The timeline's **Bodies in Field** track, off by default and enabled from the Timeline Layers
list, draws one lane per body across the visible time range, with the same field choice in its
options. It is computed from the ephemeris for the window (two years at most), so it shows bodies
for times nothing has been downloaded for.
