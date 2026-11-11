# Views and projections

```{figure} ../release/guide_assets/fig_cover.png
:width: 100%

The same multi-observatory composite as a plane-of-sky view (left) and under Helioradial (right):
the radial stretch pulls the crushed inner corona out into legible concentric shells, from the disk
out to about 200 solar radii.
```

## Projections

**Projection**, on the toolbar or in its More menu, opens a palette that changes how the Sun is mapped onto the screen.
**Orthographic** draws it in 3-D; **HPC**, **Helioradial** and **Helioradial Unrolled** are flat maps
of the sky around it; **Latitudinal** is a map of the surface. Sliders that do nothing in the current
projection are hidden, and come back when you pick a projection that uses them. **Reset view** in the
palette returns warp, crop and zoom to their defaults.

### Helioradial

The corona fades steeply with height, so an ordinary linear view gives its outer reaches almost no
room. **Helioradial** re-stretches the radial axis with one control, **Warp**, the exponent λ of a
Box-Cox stretch on heliocentric distance: λ = 1 is linear, λ = 0 is logarithmic (the default) and
λ = -1 is inverse, which pulls large distances into a finite edge. Lower values give the outer
corona more of the screen. The disk itself is never warped, so the Sun does not smear. **Crop** cuts
the picture to a disc, in solar radii, and frames that disc without changing the warp; leftmost is
automatic, no crop.

**Render in 3D** draws Helioradial as a surface the camera can orbit. With **Each image at its
observer** (on for a new session) every image layer's surface stands as its own observer saw it, as
in Orthographic: a Solar Orbiter or STEREO frame is tilted against an Earth-view frame by the
angle between the spacecraft, so the offsets between instruments show when the view is turned.
The warp moves each point along its direction from Sun centre, so a tilted surface keeps its shape.
Off, every layer faces the viewpoint, as before; a session saved before the choice opens with it
off.

**Helioradial Unrolled** unrolls the corona into a strip: position angle along one axis and the same
radial stretch along the other, so radial structure can be read at a glance.

```{figure} ../release/guide_assets/fig_radialwarp.png
:width: 90%

Helioradial: the Projection palette with its warp slider, and the resulting Sun-centred view.
```

```{figure} ../release/guide_assets/fig_rectwarp.png
:width: 90%

Helioradial Unrolled: position angle across, radial stretch up, so the composite's concentric shells
become horizontal bands.
```

### Observer Sky

Every other projection looks *at* the Sun from outside. **Observer Sky** renders the sky as seen from
the observer's own position, so you can look around the scene the way a spacecraft does, which helps
when reasoning about what a wide-field imager such as PUNCH sees. It is experimental. Pick it in the
Projection palette; its sky controls appear alongside.

## Camera and rotation

- **Camera** sets where the view is seen from: **Free**, from the active image layer's observer;
  **Follow**, from a selected object; or **Overview**, down on the solar equatorial plane. It can also
  revolve the camera about an axis.
- **Spacecraft**, a button on an image layer's panel, adds the 3D model of the observatory that
  took that layer, placed at its real position at each frame's time (models come from the 3D Model
  Manager's list; STEREO picks Ahead or Behind from the header). Press it again to remove the model.
  When no model matches, its tooltip says so. **Outside**, beside it, switches the camera to
  **Overview**, framed to hold the Sun and that layer's observer, turned so the observer sits
  straight below the Sun; press it again to put the camera back as it was.
- **3D Scene**, a section of the left sidebar, lists what is placed in 3D space: spacecraft models
  and point clouds. Its **Enlarge** (1x to 10^10x) and **Min size** (0 to 50 px across) size every
  model at once, so spacecraft stay visible at their real distances. A model's own **Enlarge
  (x scene)** multiplies the scene's, and its **Min size (x scene)** is a multiple of the scene's
  (1 is the scene's, 0 switches the floor off), so one model can stand out. The scene's sizes are
  saved with the session; **Settings > General > 3D scene** takes the current sizes as the ones a new
  session starts with.
- **FOV**, an overlay, outlines instruments' fields of view. In a 3D view (Orthographic or
  Helioradial) each field is drawn as a translucent cone from its spacecraft to as far from it as
  the Sun is, named on screen at its far end: a coronagraph's annulus about the Sun line, a
  heliospheric imager's square field to one side (STEREO HI toward Earth, SoloHI to solar east,
  WISPR ahead along Parker Solar Probe's orbit). **Cones** and **Labels**, at the top of its
  options, switch these off; with Cones off, fields are outlines on the plane of the sky. A
  field is not drawn before its instrument started observing. Off-Sun fields are drawn as squares
  at the elongations HelioFleet lists, which is the precision of a planning view, not of a
  pointing solution.
  In the flat projections (HPC, Observer Sky, Helioradial Unrolled, flat Helioradial) the fields are
  projected onto the current observer's sky. **Coverage** draws the edge of the solar surface each
  spacecraft with a field on can see, in every flat projection including Latitudinal, where
  several viewpoints' overlap shows on the map. **Bodies** marks the inner planets, Jupiter and each
  spacecraft with a field on; one off screen gets an arrow at the edge of the view with its
  elongation from the Sun. Controls that do nothing in the current projection stay visible but
  greyed, and their tooltips say where they apply; the 3D Scene's sizes are greyed in 2D for the
  same reason.
- **Track** keeps the view turning with the Sun as the movie plays; **Differential**, beside it,
  toggles differential rotation.
- **Keys on the canvas**, while it has focus (the pointer is over it): **W** and **S** pitch, **A** and
  **D** yaw and **Q** and **E** roll the scene, smoothly while held, by about 45 degrees a second
  (Option held makes it five times slower). A key moves the near side of the scene the way a drag
  that way would: D to the right, W up, Q counter-clockwise. **F** and **V** zoom in and out
  while held, at half the turn rate, **G** shows or hides the grid (the Grid row's checkbox, also
  {menuselection}`View --> Show or Hide Grid`), and **R** resets the view. **Cmd+Right** and **Cmd+Left**
  (Ctrl off a Mac) raise and lower the playback rate by one, and the new rate shows for a moment
  at the top left of the canvas; plain Left and Right step a frame, and Space plays and pauses. Pitch and yaw turn the 3D views and aim Observer Sky; the
  flat projections have no camera attitude to turn. A held key is one undo step. Shift, Cmd and Ctrl
  with a key are not camera keys (Shift annotates). Every one of these keys can be changed under
  **Settings > Keybindings > Canvas keys...** (the playback keys take only the arrows, Page Up, Page Down, Home and End), which also has **Invert pitch and yaw** and the
  turn rate. Help > Keyboard Shortcuts (Cmd+/) lists them all, with the keys that cannot be changed
  (Space, the arrows, J K L, I and O). While recording they, G and R included, are ignored unless **Record live changes** is ticked in the
  Record panel; what is turned during a recording is in its pixels, but a replay from its record
  cannot repeat it yet.
- {menuselection}`View --> Reset View` (or a double-click on the canvas) returns the view to its
  default; {menuselection}`View --> Zoom to Fit` and {menuselection}`View --> Actual Size` set the
  zoom.

## Presentation mode

**View > Present** (Cmd+Shift+P; Ctrl+Shift+P elsewhere) fills the screen with the picture alone. The
pointer in it is a large white arrow with a heavy black outline, so it shows on the black sky and on a
white frame alike, on the main canvas and on the projector's window when there are two screens. Esc
leaves. The system keys (Cmd+Tab, Cmd+Q, Cmd+H, Cmd+M, Cmd+Space) are never taken by Studio; if Cmd+Tab
seems not to work, **Help > Show Log** says whether Studio ever saw it.

## Several layers at once

- **Multiview** puts each shown image layer in a view of its own, side by side. Each view zooms
  separately while {menuselection}`View --> Separate Multiview Zoom` is on (the default for new
  installs), and {menuselection}`View --> Zoom to Fit New Layers in Multiview` fits a layer added in
  Multiview to its own view.
- **Compare** blinks between two layers (A and B) at 0.5 to 8 Hz, or swipes a vertical divider across
  the view with one on each side. Its **Difference** shows A minus B in the files' own units.
  {menuselection}`Layers --> Duplicate Layer` makes a copy of a layer to compare against, so RHEF on
  against the same data raw is one click.
- **Pizza** cuts every shown image layer into its own wedges, squares or polar tiles of one Sun, so
  the same patch is seen through every filter side by side. Nothing is resampled. The wedges follow
  solar north, so they stay on the Sun as the camera moves; in Helioradial Unrolled they become
  strips. Wedges can be labelled with each channel's wavelength, and the turn can be animated and
  recorded as a spin.

## Grid and overlays

**Grid** holds the coordinate grid, the solar limb, the Thomson and celestial spheres, the ecliptic
plane and the planets, each with its own colour, opacity and width. For the grid there are sliders
for line opacity, label opacity, line width and label size, and in the disk view a slider that swings
the radius labels to any angle, so they can be moved off the part of the image you care about. The
common move is to fade the lines to a light overlay while keeping the labels crisp.

## Tracking a CME or a comet

A CME crosses the field quickly, and in an ordinary movie it shrinks as it goes. Tracking turns that
around: pick an eruption from the CACTus catalogue and the view animates the Helioradial warp frame by
frame, so the CME's leading front stays at a fixed radius on the screen and you watch its structure
evolve rather than recede. It works in both Helioradial projections.

First load the CACTus CME catalogue as an event layer, so there are events to choose from, then open
{menuselection}`Layers --> Find CMEs to Track...` and select a CME. An option propagates each CACTus
front past the LASCO edge, by constant-speed extrapolation, out to the field of view you have loaded.
{menuselection}`Layers --> Find Comets to Track...` does the same for comets. **Cancel**, beside each
panel's status line, stops tracking and puts the warp and crop back as they were.
