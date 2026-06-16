# Add LogDisk and PowerDisk radial projections with a flat-in-disk override

## Motivation
JHV's Polar / LogPolar projections are rectilinear unwraps (x = angle, y = radius). For
coronal work it's often more useful to keep a **Sun-centered disk** view while remapping the
radial axis so faint outer structure gets screen area it otherwise loses. This adds two such
Sun-centered disk projections:
- **LogDisk** — radius mapped logarithmically (reuses the existing log scale).
- **PowerDisk** — radius mapped by a power law `r^p` (default p = 0.5), which spreads the
  mid/outer corona without the strong inner compression of a pure log.

It also adds a per-layer **flat-in-disk** override: paint a layer flat in the sky plane,
pegged to the solar limb, instead of warping it with the active radial transform — useful for
disk imagery (e.g. AIA) composited under a remapped coronagraph.

## What this adds
- `MapMode.LogDisk` / `MapMode.PowerDisk` (new `Kind.DISK`), auto-exposed in the projection
  toolbar menu; defaults p = 0.5, logDisk inner radius 0.9 R☉.
- **PowerDisk's exponent `p` is live-tunable** via a slider (0.25–2.0) in the Projection
  toolbar dropdown — enabled only while PowerDisk is the active projection, mirroring how the
  existing annotation-thickness slider sits in its split-button. `p` is read live each render
  (`PowerMapScale.power()`), so dragging it reshapes the radial mapping in real time. This is
  the heart of the mode: low `p` spreads the faint outer corona, high `p` favours the inner.
- Three fragment shaders `solarDisk{Log,Power,Flat}.frag` and their `GLSLSolarShader`
  instances; two trailing floats (`yParam`, `diskFlatRadius`) appended to the shared `Screen`
  UBO block.
- Java-side projection math (`ProjectedMap`, `MapScale`, `PolarBasis`, `MapView`,
  `PositionStatusPanel`, `GridLayer` + new `DiskGrid`) kept dual-consistent with the GLSL,
  per `docs/non-ortho-projection-note.md`.
- A per-layer flat-in-disk toggle persisted on `GLImage`.

## Deferred (follow-up PR)
A dedicated left-pane options panel (`ProjectionOptionsPanel`) bundling the radial-range
controls (logDisk/powerDisk inner/outer R☉) and a disk-grid colour picker is intentionally
**not** in this PR — those have sensible defaults, so the modes are fully usable without it.
PowerDisk's exponent, the one parameter that defines the mode, *is* shipped here (toolbar
slider, above); the deferred panel is purely the secondary range/colour tuning.

## Files
`display/{MapMode,MapScale,MapView,ProjectedMap,Display}.java`,
`math/PolarBasis.java`, `opengl/{GLSLSolarShader,GLRenderer,GLImage}.java`,
`layers/{GridLayer, grid/DiskGrid}.java`, `layers/ImageLayer.java`,
`gui/component/ToolBar.java` (PowerDisk exponent slider),
`gui/status/PositionStatusPanel.java`, `layers/filters/ImageFilterPanel.java`,
`resources/glsl/solarDisk{Log,Power,Flat}.frag`, `resources/glsl/solarCommon.frag`.

## Testing
Builds clean (`ant compile`, full tree). The three new fragment shaders were checked by hand
to resolve every symbol they reference against the current `solarCommon.frag` (ant does not
compile GLSL). **Reviewer note:** the projection visuals and the new `Screen`-UBO float
packing need an in-app look on the ANGLE/LWJGL path — the remaining smoke test.

## Open questions for the maintainer
- After #314 (unify non-ortho coordinate handling) / #316 (surface-map WCS), is a new
  Sun-centered disk remap best expressed the way this PR does it, or is there a newer seam
  you'd prefer it hook into?
- PowerDisk's exponent slider lives in the Projection split-button dropdown (enabled only for
  that mode). Is that the home you'd want for a projection parameter, or would you prefer it in
  a separate options area?
- Is appending two floats to the shared `Screen` `std140` block (used by all solar shaders)
  acceptable, or would you rather they live elsewhere?
