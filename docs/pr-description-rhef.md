# Add the Radial Histogram Equalizing Filter (RHEF) with an Upsilon midtone control

## Motivation
RHEF is a parameter-free coronal-enhancement filter that rank-equalizes pixels within
radial annuli centered on the Sun (Gilly & DeForest; sunkit-image `radial.rhef`). Unlike
the spatially-local MGN and WOW filters already in JHV, it normalizes per-radius, which
flattens the steep radial brightness falloff of the corona and reveals faint outer
structure (streamers, CME fronts) without manual tuning. It applies to any Sun-centered
imagery — SDO/AIA, SWAP, LASCO, PUNCH — and is the filter requested in #<RHEF issue number>.

## What this adds
- A new `ImageFilter.Type.RHEF` filter (decode-time, CPU), implemented with a
  sort-and-group rank kernel so cost does not scale with annulus count.
- An **Upsilon** midtone control implementing the paper's two-parameter redistribution
  (Eq. 2): `Υ_L` shapes intensities below the median, `Υ_H` above. Two independent sliders
  (each 0.05–1), defaulting to the AIA-171 recommendation `Υ_L=0.60, Υ_H=0.40` (Fig. 3), so
  RHEF looks right out of the box instead of blown out. Applied at render time in
  `solarCommon.frag` (instant slider response) and **gated to the RHEF filter only** — for
  None/MGN/WOW the layer passes `Υ=1`, so their rendering is untouched.

## How it works
RHEF must know where the Sun is and the pixel scale to define its annuli, but JHV filters
a dynamically-decoded ROI sub-image, so "assume image center" is wrong under pan/zoom.
This PR threads a minimal `@Nullable Region` (the same `roiToRegion` output the decode path
already computes) to the filter so the kernel can center its annuli correctly, including
for off-center (nonzero `CRVAL`) sources. MGN/WOW ignore the new argument (default method),
so their behavior is unchanged.

## Files
- `image/FilterRHEF.java` (new) — the rank kernel + upsilon math
- `image/ImageFilter.java` — `RHEF` enum entry; `Region` threaded into the dispatch
- `image/ImageBuffer.java`, `view/j2k/{J2KView,J2KDecoder}.java`,
  `view/uri/{URIView,URIImageReader,FITSImage,GenericImage}.java` — carry the Region to the
  filter (one-line plumbing each)
- `metadata/{MetaData,CommonMetaData,FitsMetaData}.java` — `roiToSunRegion` accessor
- `opengl/{GLImage,GLSLSolarShader}.java`, `resources/glsl/solarCommon.frag`,
  `layers/filters/ImageFilterPanel.java` — render-time upsilon + its slider; the filter
  selector is changed from radio buttons to a `JComboBox` (a 4th filter overflows the row's
  width as radios)

## Testing
Builds clean (`ant compile`). Verified the RHEF entry appears in the per-layer filter
dropdown and the kernel output matches the reference implementation. **Reviewer note:** two
items want a visual confirmation on the ANGLE/LWJGL path that a headless build can't cover —
(1) the `std140` packing of the new `upsilon` field into the `Display` block, and (2) the
off-center-Sun (nonzero `CRVAL`) annulus centering Y-sign. Both are isolated and easy to eyeball.

## Open questions for the maintainer
- Is it acceptable to widen `ImageFilter.Algorithm` so filters receive a `@Nullable Region`
  (Sun-center + scale)? This is the minimal way to give a radial filter geometry; MGN/WOW
  are untouched.
- Adding a 4th filter overflowed the radio-button row, so the filter selector is now a
  `JComboBox`. Prefer that, or keep radio buttons (wrapped to two lines)?
- The default `Υ_L=0.60, Υ_H=0.40` is the AIA-171 row of Fig. 3, applied to every layer.
  Worth keying the default off the layer's wavelength (full Fig. 3 table) instead of one
  representative pair?
