# Scope: a camera anywhere in the heliosphere, and what the celestial sphere is showing us

Written 2026-09-06 against the code, before touching it. Two asks that turn out to be one question:

- "Change the name Observer to Camera in the image layers and allow it to put the camera at
  various locations throughout the heliosphere with minimal distortion. The viewpoint layer is
  supposed to do that but it is very buggy."
- "Let the celestial sphere option in the surface field of the projection menu serve as an example
  for what needs to happen to put the observation correctly in the present-mode and exports in a
  nominal way without distortion."

The rename is done. The rest is below.

## What is true today

Two separate things decide where a pixel lands, and only one of them has a position in it.

**The scene camera is orthographic.** `GLRenderer` line 283 calls `Transform.ortho(aspect,
cameraWidth, tx, ty, rotation)`, and `solarCommon.frag` says the same thing in its own words: "the
projection is orthographic, so xy is independent of clip-space z and needs no perspective divide."
An orthographic camera has no eye point. It has a direction, an up vector and a width. There is no
value in it that means "the camera is 66 solar radii from the Sun."

**The data is placed on a surface**, chosen by `SurfaceModel`: plane of sky, Thomson sphere, or
celestial sphere. These are the spheres of diameter L through the Sun tangent to the plane of sky,
z = r²/L, with L = infinity, D and 2D. That family is real 3D geometry, parameterised by one
number the shader morphs through (`depthFactor`).

So the surface knows about distance and the camera does not.

## Why the Viewpoint layer cannot do what is being asked of it

`UpdateViewpoint.Equatorial`, which is the "Heliosphere" camera mode, opens with

    private static final double distance = 2 * Sun.MeanEarthDistance / Math.tan(Math.toRadians(0.5));

That is **229 au** (computed from the constant). Nothing is 229 au away. The number is chosen so
that a half-degree field spans 2 au, which is to say: it puts the camera so far off that the
perspective vanishes and the orthographic projection is a good approximation of the view. The
"Heliosphere" mode is not a camera in the heliosphere. It is a direction to look from, at a
distance picked to make the absence of perspective invisible.

That is the whole difficulty, stated by the code itself. Everything else follows from it:

- **`Location` puts the camera inside the field, where orthographic has no answer.** Solar Orbiter
  at 66 solar radii against a 245 solar-radii mosaic is not a direction, it is a place, and half
  the field is behind it. The Thomson-sphere conflict already documented in
  `ViewpointLayerOptions.enforceSurfaceExclusivity` ("every strange render chased down on
  2026-08-30 came back to that one pairing") is this same fact arriving through the surface: the
  Thomson sphere reaches only as far as the observer, so from inside there most of the picture has
  no surface to sit on. The workaround switches the surface. The cause is that the camera cannot
  be where it is being asked to be.
- **`Location` fails silently to Earth.** `UpdateViewpoint.Location.update` returns
  `Sun.getEarth(time)` whenever the position load is null or its response has not arrived. Pick a
  spacecraft, and until the ephemeris lands (or forever, if it fails) the camera is at Earth while
  the menu says otherwise. Nothing reports this. This one is a plain defect and is worth fixing
  whatever happens to the rest.
- **The camera is global, the layers are not.** Each image layer's data is reprojected from its own
  observer to the scene viewpoint. When the scene viewpoint moves somewhere no instrument was, the
  reprojection is an extrapolation, and it is the surface model that decides what it extrapolates
  along. Moving the camera and choosing the surface are not independent controls, and today they
  are presented as if they were.

## What the celestial sphere is an example of

It is the one surface in the family that is defined by the observer's own sky rather than by a
scattering model: radius D about the observer, so a line of sight at elongation e lands at
2D sin(e/2), and the picture on it is the picture a planetarium would put on a dome. Its virtue is
not accuracy about depth (`SurfaceModel`'s own comment is blunt: this is "a placement model, not a
measurement"). Its virtue is that **it is the placement that does not need the camera to be
anywhere in particular**: angles are preserved by construction, so the rendering is nominal for
any camera that sits at the observer.

That is why it is the right example to point at. A picture that is correct in present mode and in
an export is one where the mapping from sky angle to output pixel is stated and preserved, not one
where a projection chosen for on-screen inspection is carried through to a file.

Worth recording, because it bounds what is broken: the **aspect** handling in export already does
not stretch. `Display` (lines 248-255) letterboxes the render area to the requested output aspect
rather than scaling the scene into it, and `GLGrab` grabs the canvas rather than `fullViewport`
for that reason. So if a distortion is being seen in present mode or export, it is not the aspect
ratio, and I need to be told which projection and surface it appears under before guessing.

## What it would take

Three pieces, in order, each useful alone:

1. **Report the Location fallback.** Half a day. `Location.update` returns Earth silently; make it
   say so on the layer's status line and stop pretending. Nothing else changes.
2. **A perspective option for the scene camera.** `Transform.ortho` becomes one of two, with the
   camera carrying an eye distance and a field of view. Everything that reads `cameraWidth` has to
   learn about a frustum: `MapScale`, the warp mesh, `ViewportMath`, mouse picking, and the
   annotation and grid overlays that currently assume xy is independent of depth. This is the
   large piece and it is where a mistake shows up as a subtly wrong picture rather than an error.
3. **A camera whose position means something.** With 2 in place, the Camera layer stops being an
   orbit driver and becomes a placement: a point in the heliosphere, a look direction, a field of
   view, animated over time. The celestial sphere is then the default surface for it, because it
   is the one that stays nominal as the camera moves.

Doing 3 without 2 is what the Viewpoint layer already attempts, and 229 au is the shape of the
compromise it had to make.

## Not started

Only the rename is done (`ObserverLayer.getName` returns "Camera"). Step 1 is small and I would
take it next; steps 2 and 3 are a project, not an afternoon, and should be agreed before starting.
