# Views and projections

```{figure} ../release/guide_assets/fig_cover.png
:width: 100%

The same multi-observatory composite as a plane-of-sky view (left) and under Helioradial (right):
the radial stretch pulls the crushed inner corona out into legible concentric shells, from the disk
out to about 200 solar radii.
```

## Projections

**Projection**, on the toolbar, opens a palette that changes how the Sun is mapped onto the screen.
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
- **Track** keeps the view turning with the Sun as the movie plays; **Differential**, beside it,
  toggles differential rotation.
- {menuselection}`View --> Reset View` (or a double-click on the canvas) returns the view to its
  default; {menuselection}`View --> Zoom to Fit` and {menuselection}`View --> Actual Size` set the
  zoom.

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
{menuselection}`Tools --> Find CMEs to Track...` and select a CME. An option propagates each CACTus
front past the LASCO edge, by constant-speed extrapolation, out to the field of view you have loaded.
{menuselection}`Tools --> Find Comets to Track...` does the same for comets. **Cancel**, beside each
panel's status line, stops tracking and puts the warp and crop back as they were.
