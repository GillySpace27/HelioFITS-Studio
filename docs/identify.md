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
- **View > Identify Stars** includes stars (see "Stars" below).
- **The Identify panel** (right sidebar; the toolbar's More menu has its button) holds the three
  switches, the pointer tolerance in screen pixels (default 6), the label delay (400 ms), the
  near-Earth observer uncertainty (7,400 km), light travel time (off), Pluto (on), and the star
  options (IAU names, Bayer designations, faintest magnitude). Above them it shows what the
  identifier matched under the pointer and why: for each body the layer, how far the pointer was
  from the body's centre against the allowed radius (the body's angular radius plus the pointer
  tolerance, in degrees), its distance, its angle from the Sun and any qualifier; and, for each
  visible image layer, the observer and frame time the identification used. The last answer stays
  after the pointer leaves the image (marked as old), **Freeze this result** holds it, **Copy**
  puts the settings, result and observers on the clipboard as text for a bug report, and **Reset**
  restores the numbers and finer switches (the three main switches stay as they are).
- **Glow around what is identified** (Identify panel, on) draws a soft glow along the outline of
  the body named under the pointer, and **Pulse when it changes** (on) sends a short ring outward
  from that outline when the pointer reaches a different body. The outline is the body's own
  perimeter as the current view draws it: the ring of sight lines that grazes the body's limb, as
  seen by the observer of the layer that named it, put on the page by the identifier run backwards
  (as pins are placed). It is round in the orthographic view and stretched where a helioradial or
  unrolled view stretches the sky. A body only a few pixels across keeps its shape, enlarged about
  its centre to 7 points so the glow can be seen. The Sun is not outlined (it is under the pointer
  everywhere on the disk), nor yet are models, orbit plot markers, events or stars. Neither is
  ever drawn into a grab, an export or a recording. identifySet takes `glow` and `pulse`.
- **Settings > Identify** now only points at the panel.

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
| `identifyPin` | Pin, unpin, toggle, clear or list pinned body labels |
| `identifyFindBody` | View > Find Body...: choose a body, pin it, and say where it is |

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

## The 3D view

In the Orthographic view the identifier also names what the scene draws as objects:

- **Model layers** placed at an ephemeris target (Managers > 3D Model Manager, or an image layer's
  Spacecraft button) are named by their target when the pointer is within the model's drawn size
  or the pointer tolerance of its centre, unless the Sun is in front of it. The label gives its
  distance from the Sun.
- **Orbit plot markers** (the Viewpoint layer's overview camera) are named in the footer; the
  orbit plot draws its own label beside them.

When the camera is turned more than 1 degree from an image layer's line of sight (orbited, by
dragging in Rotate mode or with a rotate command; a roll about the line of sight does not count),
that layer names only bodies on its imagery. A body behind the Sun, behind the occulter or off
the field is not drawn anywhere in an orbited view, so it is not named, and with no image layer
the empty sky names nothing. Turned back to within 1 degree, every ephemeris body is named again.

## Events, comets and FOV outlines

With the identifier on, the footer also names, in every view mode:

- **Catalog events** the SWEK layer draws under the pointer, by the same hit test as its popup:
  "AR 13664" when the event carries a NOAA number, otherwise its group ("Flare", "Coronal Mass
  Ejection"). The popup stays the event's label; the identifier adds no second one.
- **The tracked comet** (Track comet panel) where its track puts it at the current time, while
  the tracker follows it. The track is SOHO's view (Comets), so it is placed on the plane of sky
  and seen from the first visible layer's observer.
- **FOV outlines** (the FOV layer) the pointer is inside, as "AIA & HMI (Earth orbit)": the sight
  line from each platform's observer onto its plane of sky, against the outline as drawn, Solar
  Orbiter rolled by its attitude.

## Pins and Find Body

Alt-click a named body to pin its label to the view; Alt-click it again to unpin it. A pin shows
"+ Name" at the body and follows it through time and every view mode the identifier works in. It
sits exactly where hovering would name the body, because its place comes from running the
identifier's own sight line backwards (Newton's method on the screen), not from a second
projection. A pin whose body is off the page, behind the Sun in the 3D view, or in a mode with no
sky is not drawn. Pins are saved with the session.

**View > Find Body...** asks for a body, pins it, and says where it is from the active layer's
observer (Tx, Ty, angle from the Sun) and whether that layer's imagery holds it. The
`identifyPin` command pins, unpins, toggles, clears or lists pins.

## Stars

With View > Identify Stars on, the footer names the nearest star within the pointer tolerance: the
Hipparcos catalogue the Stars overlay draws, at the same directions, down to the faintest magnitude
set in Settings > Identify (the overlay need not be shown). The name is the IAU proper name
("Alpheratz") when IAU names is on, else the Bayer designation ("α And") when that is on and the
star has one, else the Hipparcos number ("HIP 677"). The tooltip adds the V magnitude and the
Hipparcos number.

Names come from the IAU Catalog of Star Names of the IAU Working Group on Star Names (about 450
named stars, CC BY), fetched once from the WGSN table and kept beside the Hipparcos catalogue in
the catalogs folder; Bayer letters come from the same table, so only IAU-named stars have one here.
Both tables load in the background the first time the switch is on, from disk if cached. Stars are
not named in the 3D view when the camera is turned away from the image layer's line of sight,
because the sky drawn there is no observer's.

Checked live on a PUNCH CAM frame (2026-04-25): Alpheratz, Mirfak, Algol and Sheratan were named
at the screen points predicted from their astropy positions in that frame.
