# Scope: Observer Sky as a secondary transform, for a planetarium

Written 2026-09-05 against the code, before touching it. Gilly's ask: make the Observer Sky part of
the Projection palette "activatable as a secondary transform on top of the main selection, in such
a way as to support easy planetarium adoption of the present mode."

## What Observer Sky is today

A `MapMode` like the others, with its own fragment shader (`solarSky.frag`). For each page pixel it
computes a ray in the observer's frame from an aim (`skyLook`, lon/lat), a sky projection
(gnomonic, stereographic, azimuthal equidistant; `SkyProjection`) and a field, intersects the ray
with the surface model, and samples the image there. `SkyMap.java` carries the same geometry for
the grid, so the overlays agree with the picture. It is the last entry in the menu because it is
the only one not centred on the Sun.

So it is already "the sky": a dome master is Observer Sky with the azimuthal equidistant projection
and a 180-degree field, aimed at the zenith of the dome. What it is not is *composable*: choosing
it means not choosing Helioradial, and the whole reason to want the composition is to put the
Helioradial-warped corona on a dome.

## What "secondary transform" has to mean

The present mode produces a picture parameterised by position angle and (possibly warped)
elongation. A dome pixel also stands for a position angle and an elongation from the aim. So the
secondary transform is: for each dome pixel, work out (PA, e) of its ray, feed that pair through
the present mode's own forward mapping to a page coordinate, and take the colour the present mode
would have put there. Helioradial's radial stretch survives, which is the point: the dome shows
the warped corona, with the warp reading as a change of angular scale, exactly as it does on the
flat screen.

## Two ways to build it

**Fold it into the shaders.** Each `solar*.frag` is a `main()` that goes page -> geometry ->
`getColor()`. Composition would need a shared function per mode, "sample at (PA, e)", which is a
refactor of all seven and a second code path through each. Correct, and the biggest change to the
projection code since the warp mesh.

**Two passes.** Render the present mode as it is into an offscreen texture, the way `GLGrab`
already renders a pass for EXR export, then draw the dome by sampling that texture with the mode's
own (PA, e) -> page mapping. That mapping exists on both sides already: `MapScale` in Java and
`unwarpRadius` in GLSL are the same function, and `bindScreen` puts it in a uniform block. No
projection shader changes. Cost: one render target the size of the viewport per frame, and a
second draw. Everything the first pass does, sharpen, HDR, difference mode, RHEF, is inherited
because it is in the pixels.

The second is the one to build. It is also the one that handles the overlays for free if they are
rendered into the first pass, and the one where a mistake shows up as a wrong picture rather than
as a wrong picture in one of seven shaders.

## What the palette grows

Under the projection radio buttons, a check box: "Also project onto the observer's sky". When on,
the existing Observer Sky sub-panel (Sky projection, Field, Aim) enables and governs the second
pass, and the projection radio buttons keep choosing what is drawn in the first. Observer Sky as a
primary mode stays as it is, since with the check box it becomes the degenerate case of the
composition and there is no reason to remove a working thing.

A planetarium preset beside it: azimuthal equidistant, 180 degrees, aimed at the zenith, square
output. That is a dome master, and it is three settings, so it is a button rather than a feature.

## Where it gets difficult

- **Mouse and grid.** `mouseToMap` and the grid overlays would have to go through the second
  mapping too, or the cursor readout and the grid describe the first pass while the eye sees the
  second. `SkyMap` already does the sky half; composing it with `MapScale` is the same arithmetic
  as the shader's, in the other direction.
- **Multiview.** Each viewport needs its own offscreen target. Fine, but it is where the memory
  goes.
- **Recording and export** capture the canvas, so they get the dome for free. EXR export renders
  its own pass through `GLGrab`; it would need to be told which pass it is exporting, the data or
  the dome. The data, presumably, and the dome is the movie.
- **What the corona looks like on a dome** when the present mode is Helioradial at lambda well
  below 1: the outer field is compressed toward the Sun, so on a 180-degree dome the corona
  occupies a small cap around the aim. That is correct and may be what a planetarium wants (the
  audience looks at a bright, compact Sun), or it may want the field spread across the dome, which
  is Observer Sky as a primary mode with lambda = 1. The preset should say which it is doing.

## Estimate

Two-pass rendering with the check box and the preset, mouse and grid following: a few days,
mostly in the offscreen plumbing and in making `GLRenderer`'s viewport loop draw twice. The shader
work is one new fragment shader that samples a texture through `unwarpRadius`.

Not started. The celestial sphere third state shipped instead, because it is small and it is half
of the same question: the sky at the Sun's distance is what a dome shows.
