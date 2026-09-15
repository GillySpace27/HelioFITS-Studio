# PR design: indexed Carrington synoptic map as a full-sphere layer

## Context
Gilly has hand-annotated Carrington/synoptic maps (`carrington_maps_handmade/ptmc_compo_sm_*.fits` —
one per rotation, e.g. `cr2302DO`) produced by an IDL+Photoshop pipeline: a full 360°×180°
equirectangular chart, 8-bit indexed (values 0-19), where each index is a fixed category —
coronal-hole boundaries (+/-), polarity regions, neutral line, filaments, sunspots, plage, border/
markers — documented in `20_in_Color_Table.docx` as an exact index→RGB legend. He wants these to
render draped on the full 3-D solar sphere in JHV (all longitudes, both poles, visible from any
angle — like a globe, not a photograph), loadable the normal way (`File > Open Image Layer…`), with
more such maps loadable later with zero extra steps.

## Target architecture (verified against upstream/master, tip at investigation time)

**The full-sphere texture-drape already exists and ships today** — this is not new rendering
machinery, it's a data/metadata integration gap. `resources/glsl/solarCommon.frag` /
`solarOrtho.frag` already ray-cast the unit sphere per fragment, and for a WCS projection tagged
`CAR`/`CEA` (`WcsHeader.Projection.isSurfaceMap()`) convert the 3-D hit point directly to
heliographic lon/lat and sample the image texture with seam wraparound (`fract` on longitude); the
hit point is rotated by the image's own `sourceViewQuat`, not the camera, so the texture stays
glued to the Sun's rotating frame under interaction — full 360° wrap, correct pole behavior, "New
Synoptic Layer" already loads real HMI/AIA Carrington maps this way. `WcsHeader.Projection.fromCtype`
(`src/org/helioviewer/jhv/wcs/WcsHeader.java`) is invoked generically for *every* FITS file via
`WcsInterpreter` → `FitsMetaData` (`src/org/helioviewer/jhv/metadata/FitsMetaData.java:270-295`) —
not specific to the synoptic-fetch dialog — so once a file is correctly recognized, the existing
generic `Open Image Layer…` local-file path already routes it through the surface-map shader with
**no new UI**.

Two real gaps, both concrete and small:

1. **Header recognition.** This file's `CTYPE1`/`CTYPE2` are the literal strings `'Longitude'`/
   `'Latitude'` — not a standard WCS projection code — so `fromCtype` falls through to `TAN`
   (wrong: renders as a small mis-projected patch, not a sphere wrap).
2. **Categorical color handling.** The pixel value → color path in `solarCommon.frag` is:
   `value = fetch(image, texcoord, brightness)` (a per-layer min/max stretch: `texture(tex,coord).r *
   bright.y + bright.x`) → `texture(lut, vec2(value, 0.5))`. Two problems for index data:
   - **Stretch runs before the LUT lookup.** `brightness` (offset/scale) is normally auto-fit to the
     data's min/max for continuous intensity data; for an index map that remaps index 2→0 and
     index 13→255, destroying the exact index→legend correspondence. `GLImage.setBrightness(offset,
     scale)` (`src/org/helioviewer/jhv/opengl/GLImage.java:209`) needs to be forced to identity
     (`offset=0, scale=1`) for this layer type, and the slider that drives it
     (`RangeSliderFilterPanel.levels(layer)`, wired in
     `src/org/helioviewer/jhv/layers/selector/ImageLayerOptions.java:53`) must not be left live —
     dragging it would silently corrupt the mapping.
   - **The data texture uploads with hardcoded `GL.LINEAR`.** `GLImage.streamImage()`
     (`GLImage.java:74`, `tex.copyImageBuffer(imageData.imageBuffer(), GL.LINEAR)`) blends
     neighboring raw index values at every category boundary before the LUT ever sees them (e.g.
     averaging index 2 and index 10 produces a meaningless ~6, then that gets looked up as a wrong
     color). Needs `GL.NEAREST` for this layer type. Not currently parameterized — real code change.

**The LUT/auto-colormap system already has exactly the mechanism needed, unrelated to the fix
above.** `LUT` (`src/org/helioviewer/jhv/image/lut/LUT.java`) is a `record(name, rgba: ByteBuffer)`;
built-in colormaps are 256-entry rasterized ramps (`.ggr` GIMP-gradient files under
`resources/luts/`). A declarative `ColorRule(observatory, instrument, detector, measurement, lut)`
list, read from `resources/settings/colors.js` (`LUT.readColorRules`, `LUT.java:165-168` — despite
the `.js` extension it's a plain JSON array, e.g. `{"instrument":"SECCHI","detector":"COR1",
"color":"STEREO COR1"}`), matches on `FitsMetaData` fields and auto-selects a layer's default
colormap. `getDetector()` is populated from the FITS `DETECTOR` header
(`FitsMetaData.java:135`, `detector = m.getString("DETECTOR").orElse("")`) — this file has no
`DETECTOR`/`OBSERVATORY`/`INSTRUME` keywords at all, so this mechanism can be reused for free by
having the new detection branch synthesize a detector label.

## Design

**1. Detection** — `FitsMetaData`, alongside the existing observatory/detector-specific branches
(same pattern as the `C2`/`C3`/`STEREO-*` occulter-radius branches at `FitsMetaData.java:93-108`):
when `ORIGIN` starts with `ptmc_compo` (the prefix this IDL pipeline already writes on every file —
confirmed stable across rotations, zero changes needed to Gilly's pipeline), set the synthetic
`detector = "CHPOL"` and a new boolean, `isIndexedSurfaceMap = true`, and force
`projection = WcsHeader.Projection.CAR` directly (bypassing `fromCtype` for this case rather than
loosening the shared matcher for every FITS file in the codebase).

**2. Color** — one new resource, `resources/luts/CHPolarity.ggr` (or equivalent 256-entry raster),
built once from the 20-entry table in `20_in_Color_Table.docx`: each defined index gets a flat
1-unit-wide color band, no gradient (a step function, not a ramp — matches how a categorical legend
must render). Register it exactly like an existing colormap; add one `colors.js` entry —
`{"detector":"CHPOL","color":"<lut name>"}` — so it auto-selects via the *existing* `ColorRule`
match, no new selection logic. Still swappable from the normal LUT dropdown if ever wanted.

**3. Rendering correctness** — gated on `isIndexedSurfaceMap` (exact plumbing from `MetaData` through
to `GLImage`/`ImageLayerOptions` — Gilly's other layers already carry per-layer metadata references
this way; confirm the precise accessor chain during implementation, not a design-level unknown):
   - `GLImage.streamImage()`: texture filter parameter, `GL.NEAREST` instead of `GL.LINEAR`, for
     this layer type only — everything else keeps `GL.LINEAR`.
   - Default + lock `brightOffset=0, brightScale=1` at layer construction; hide/disable the levels
     panel (`RangeSliderFilterPanel.levels`) in `ImageLayerOptions` for this layer type. No existing
     precedent in this codebase for a layer type hiding a filter panel — new, small conditional.

**4. Data source** — render `PRIMARY` HDU only (4013×2011, the already-correctly-scaled 2:1
equirectangular product: `NAXIS1×CDELT1 ≈ 360°`, `NAXIS2×CDELT2 ≈ 180°`, `CRVAL2=-90` = south pole
at row 0). `FULL` (5721×3477, pre-crop working geometry — `LONG`/`LAT` extensions are pixel-index
crop bounds into `FULL`, not degree values, despite the `CTYPE`-style extension names) and `POL`
(a separate float, -1..1, polarity plane) are out of scope this pass — provenance/intermediate data,
not the rendered product.

## Non-goals (this pass)
- Arbitrary user-supplied LUTs / a "load a custom legend" UI.
- A manual "treat as indexed" override toggle (only the `ptmc_compo` ORIGIN-prefix auto-detect).
- Rendering `FULL` or `POL`.
- Any FITS not carrying the `ptmc_compo` ORIGIN prefix.
All cheap to add later against a real second use case; not building ahead of one.

## Verification
- Load `ptmc_compo_sm_20250909_041922_cr2302DO_l3.fits` via `File > Open Image Layer…` end to end;
  confirm it wraps the full sphere with no seam, both poles reachable by rotating, and rendered
  colors match the docx legend exactly (spot-check several index values, not just a visual glance).
- Confirm the levels slider is genuinely inert/hidden for this layer, not just defaulted.
- One assert-based check on the new `ORIGIN`-prefix detection branch (the one piece of non-trivial
  new logic) — e.g. a small `test_*` or `main`-guarded self-check confirming `ptmc_compo_*` origins
  set `isIndexedSurfaceMap`/`detector=CHPOL` and non-matching origins are unaffected.

## Open risks / things to verify empirically during implementation
- **`CRPIX1=0`, `CRPIX2=0`**: non-standard (FITS reference pixels are conventionally 1-indexed).
  Given `NAXIS1=4013` spanning ~360°, a 1-pixel offset is sub-pixel-scale and likely negligible, but
  confirm the `imageToPlane` transform (`WcsInterpreter`) doesn't produce a visible half-pixel seam
  or pole-alignment error with this specific header convention.
- **North/south orientation**: `CRVAL2=-90` puts row 0 at the south pole in the source data; verify
  this matches `solarCommon.frag`'s existing `asin`/texcoord-y convention for surface maps (an
  upside-down globe would be an easy, silent mistake) — empirical check against the real render, not
  inferable from code alone.
- **LUT raster resolution**: assumed 256 entries throughout (matching the existing `.ggr`-based
  colormaps); confirm the categorical LUT can be built at that resolution with each of the ~14
  defined indices (0-13, 16-19; 14-15 explicitly unused per the docx) landing on an exact,
  non-interpolated band.
