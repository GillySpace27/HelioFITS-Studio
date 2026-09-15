# Add a PowerDisk Sun-centered radial disk projection

## Motivation
JHV's Polar / LogPolar projections are rectilinear unwraps (x = angle, y = radius). For
coronal work it's often more useful to keep a **Sun-centered disk** view while remapping the
radial axis so faint outer structure gets screen area it otherwise loses. This adds one such
projection, **PowerDisk**, with a single tunable control that spans the whole useful family.

## What this adds (commit 1)
- **`MapMode.PowerDisk`** (new `Kind.DISK`), auto-exposed in the projection toolbar menu.
- **A radial exponent `p`**, the Box-Cox power transform `(rᵖ−1)/p`, on a live slider in the
  Projection dropdown:
  - `p = 1` linear, `p = 0` logarithmic (the exact limit `→ ln r`), `p = −1` inverse
    (`2 − 1/r`, bounding `r = ∞` to a finite edge). Default `p = 0` (log), which sits mid-slider.
  - **Linear inside the limb:** the warp is applied only to the corona (`r > 1 R☉`); the disk
    itself (`r ≤ 1`) stays an undistorted, linearly-scaled sphere, C¹-continuous at the limb. So
    the on-disk image is never radially smeared, at any `p`.
- **Automatic flat-in-disk for disk imagers.** A disk imager (FOV dominated by the disk) is
  rendered flat in the sky plane rather than radially warped — the radial warp polar-resamples
  and smears the disk center, flat does not. This is decided **live in the renderer** from the
  layer metadata, so it holds regardless of layer load order; there is no manual toggle to get
  wrong. Coronagraphs keep the warp.
  - Disk-vs-coronagraph is detected by field of view (`ImageBounds.inscribed < 2 R☉`), because
    JP2/Helioviewer sources carry no occulter metadata — `innerRadius`/cutoff/mask are FITS-only,
    so a JP2 LASCO reports `innerRadius = 0`, identical to AIA. FITS coronagraphs (PUNCH) also
    report `innerRadius ≥ 1`.
- **Radial range** — a double-ended slider (same dropdown) sets the inner/outer radius (R☉) the
  disk shows; its maximum tracks the loaded layers' extent (a `Layers.Listener`), so the outer
  handle at the top means "fit to the data".
- **`DiskGrid`** — Sun-centered rings + angular spokes drawn in the disk's isotropic world
  space. The grid layer's **Longitude** step drives the spoke spacing; the lat/lon-graticule
  controls that don't map to a radial grid (Grid type, Latitude) are greyed out while a disk
  projection is active.
- Fragment shaders `solarDisk{Power,Flat}.frag` + `GLSLSolarShader` instances; two trailing
  floats (`yParam` = p, `diskFlatRadius`) appended to the shared `Screen` UBO block. Java-side
  projection math (`ProjectedMap`, `MapScale`, `PolarBasis`, `MapView`, `PositionStatusPanel`)
  kept dual-consistent with the GLSL per `docs/non-ortho-projection-note.md`.

## Also in this PR (separable commits)
These are committed separately and could be split into their own PRs if you'd prefer:

- **Double-ended radial mask** (commit 2, per layer) — the band between two handles is shown:
  the low handle masks the disk inward from center, the high handle masks the corona inward from
  the edge. This is what gives a coronagraph a **transparent central hole** (so a disk imager
  composited below shows through) and/or an outer crop — the core of a clean coronagraph-over-disk
  composite. Normalized to the layer's inscribed radius, so resolution is consistent across
  fields of view (incl. unbounded-FOV layers like AIA, where `getOuterRadius()` is not finite).
- **Grid color / opacity / line-width** (commit 3) — per-grid-layer styling applied across the
  orthographic graticule, the polar/flat map grid and the disk rings/spokes (axis, Earth and
  radial-distance overlays keep their semantic colors); labels fade with the opacity too. General
  grid feature, mirrors the existing annotation color/thickness options — the most natural one to
  split out.

## Testing
Builds clean (`ant compile`, full tree; each commit compiles on its own). Fragment shaders were
checked by hand against `solarCommon.frag` (ant does not compile GLSL) and smoke-tested in-app on
the ANGLE/Metal path: PowerDisk across `p = −1…0…1`, the `p = 0` log endpoint (verified visually
indistinguishable from a true-log render), auto-flat on AIA, and a multi-instrument
AIA + LASCO C2/C3 composite. **Reviewer note:** the new `Screen`-UBO float packing is the one
item a headless build can't cover.

## Open questions for the maintainer
- After #314 (unify non-ortho coordinate handling) / #316 (surface-map WCS), is a Sun-centered
  disk remap best expressed the way this PR does it, or is there a newer seam you'd prefer?
- The exponent and radial-range sliders live in the Projection split-button dropdown (enabled
  only in disk modes). Right home, or a separate options area?
- Is appending two floats to the shared `Screen` `std140` block acceptable, or elsewhere?
- The mask and grid-styling commits are bundled here for context but are independent — say the
  word and either becomes its own PR.
