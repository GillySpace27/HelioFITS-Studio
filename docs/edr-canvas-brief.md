# Brief: an EDR canvas, so the corona is brighter than the window

Written 2026-09-04, branch `edr-canvas` off `demo-all` at 13302. Companion to
`deep-colour-canvas-brief.md`, which got the canvas past 8 bits; this gets it past white.

## What is wanted

On the built-in Liquid Retina XDR panel, image layers should render brighter than the
interface white, up to what the panel can deliver at its current brightness setting. Text,
grid, legend, timestamp and the Swing chrome stay at normal brightness. The corona is the
thing that makes you squint, not the clock.

The export path, snapshots and the layered EXR's data channels are untouched: what you ship
does not change, only what you watch.

## What was measured before writing this

All on the M4 Max, macOS 26.6.1, 2026-09-04, with `extra/test/native/edr_present_probe.m`.

- `NSScreen.maximumPotentialExtendedDynamicRangeColorComponentValue` = 16.0.
- A `CAMetalLayer` (RGBA16Float, `wantsExtendedDynamicRangeContent = YES`) tagged
  **extended sRGB** does not engage EDR: the screen's current headroom stays at 1.0. Values
  above 1.0 in such a layer go nowhere.
- The same layer tagged **extended linear sRGB** engages it: current headroom rises to
  **10.85** while the layer is on screen, and falls back to 1.0 when it goes away.
- The RGBA16F IOSurface pbuffer path was proven in `IOSurfacePbufferProbe` (round-trip error
  7.3e-5) on 2026-08-31.

So the presenter must produce linear light itself. A plain blit of the sRGB-encoded canvas
into a linear-tagged layer would show every mid-tone a stop too bright; a blit into an
sRGB-tagged layer would show nothing above white. The conversion pass is not optional.

## Design

Decision taken with Gilly on 2026-09-04: **flat gain** (option A). The whole image layer is
multiplied by one gain after the colour table; "auto" means the panel's current headroom, so
LUT white lands on peak brightness and everything scales with it. The alternative, a ramp
that leaves mid-grey alone and expands only the highlights, was declined.

### 1. Canvas: RGBA16F IOSurface

`AngleRenderer`'s deep path allocates the IOSurface as `'RGhA'` (8 bytes per pixel, half
float) instead of `'l10r'`, and the pbuffer's `EGL_TEXTURE_INTERNAL_FORMAT_ANGLE` becomes
`GL_RGBA` with `EGL_TEXTURE_TYPE_ANGLE = GL_HALF_FLOAT`. Blending happens in half float, so
values above 1.0 survive compositing. `jhv_deep_canvas_create` grows a format argument.

### 2. Shader: one gain, image layers only

`DisplayBlock` gains `float hdrGain` (the block's rounding floats are all taken, so the block
grows by one 16-byte row on both sides; the Java `put()` sequence mirrors it). At the end of
`solarCommon.frag`'s colour path:

    return texture(lut, vec2(value, 0.5)) * display.color * display.hdrGain;

Overlays do not use this shader and are never multiplied. `GLImage` passes gain 1 whenever
`capture != Capture.NONE`, so exports, snapshots and the data channels see today's values.
The clipping flags and the raw-output path are before the multiply and unchanged.

The value comes from `Display.hdrGain()`: a user setting, either a fixed number in [1, 16]
or "auto", which reads the presenter's headroom query each frame. Persisted as
`display.hdrGain` (`auto` or a number).

### 3. Presenter: RGBA16Float layer, linear tag, conversion pass

`jhv_metal_host_prepare_deep` takes a mode. In EDR mode the layer becomes
`MTLPixelFormatRGBA16Float`, `wantsExtendedDynamicRangeContent = YES`, colorspace
`kCGColorSpaceExtendedLinearSRGB`, and the vertical-flip transform is dropped.

`jhv_metal_host_present_deep` replaces the blit with a render pass: one full-screen triangle
sampling the IOSurface texture (nearest), fragment output per channel

    linear(c) = sign(c) * (|c| <= 0.04045 ? |c| / 12.92 : ((|c| + 0.055) / 1.055) ^ 2.4)

applied to RGB (this is the sRGB EOTF, extended past 1.0 the way Apple's extended spaces
define it), alpha passed through, the flip done in the vertex stage. The MSL source is a
string compiled once at first present; no `.metallib` build step. The 10-bit blit path stays
in the file for the fallback.

New query `jhv_metal_host_edr_headroom(layer)` returns
`maximumExtendedDynamicRangeColorComponentValue` of the screen holding the layer's window
(1.0 when EDR is not engaged). Cheap; polled per frame by the auto gain.

### 4. Fallback ladder and switches

    display.edrCanvas       default true   -> RGBA16F canvas, EDR layer, gain applies
    display.deepColorCanvas default true   -> RGB10_A2 canvas, 10-bit layer (today's path)
    both false                              -> 8-bit ANGLE window surface

If the RGBA16F pbuffer or the EDR layer fails to come up, the renderer drops one rung and
logs it, exactly as the deep path already does. Startup logs what it actually got:

    EDR canvas: RGBA16F IOSurface pbuffer, CAMetalLayer RGBA16Float linear, headroom=10.85

### 5. UI

View menu: a check item "HDR canvas" (bound to `display.edrCanvas`, takes effect on next
canvas attach, says so in its tooltip) and a submenu "HDR brightness" with Auto (display
maximum) and fixed stops 1x, 2x, 4x, 8x. A slider is not worth a dialog for five values.

## Verification

1. `extra/test/native/edr_present_probe.m`: asserts the screen headroom rises above 1.0 after
   presenting into a linear-tagged EDR layer. Fails loudly if it does not.
2. `IOSurfacePbufferProbe` already covers the RGBA16F round trip; re-run, not re-derived.
3. SDR fidelity: with gain 1, a screenshot of the legend bar on the EDR path must agree with
   the 10-bit path within the 2 counts that path was measured at. This is the test that the
   linearization is right. An untagged float layer measured 40/255 off; that number is the
   failure mode this test exists to catch.
4. Export fidelity: the layered EXR from the EDR build is byte-identical to one from the
   10-bit build for the same state (gain must never reach the capture path).
5. Acceptance: Gilly, the Sun, squinting.

## Out of scope

Tone mapping, reference-white modes, HDR export formats, external displays without EDR
(they get today's path via the ladder), Windows and Linux (no CAMetalLayer; the ladder stops
at their existing configs).

## Outcome, 2026-09-04

Built and measured on the M4 Max, macOS 26.6.1, revision 13309 on `edr-canvas`.

**It works.** Startup logs `EDR canvas: RGBA16F IOSurface pbuffer, CAMetalLayer RGBA16Float
tagged extended linear sRGB`; with an AIA 171 layer and the gain on Auto the log then reads
`EDR headroom now 5.60 of a potential 16.0 SDR whites; gain setting auto -> 5.6048427`, the
canvas readback shows thousands of pixels above 1.0, and the screen reports 6.23 (it peaked at
16.0 for a moment during one run). At gain 1 the canvas never leaves [0, 1] and the screen
stays at 1.0.

**Three things the brief did not know, all found by measurement:**

1. *The compositor engages EDR on content, not on request.* A linear-tagged layer with values
   at or below 1.0 leaves the screen at 1.0; 1.1 does not engage, 1.25 does. So `auto` has to
   bootstrap: on a screen whose potential headroom is above 1, the first frame renders at
   1.5x, the compositor ramps the headroom up over one to two seconds, and the renderer polls
   the reading every 250 ms for four seconds after each present, repainting on change. Without
   the polling the app, which renders on demand, sat at the bootstrap gain forever.
2. *The gain is a multiple of SDR white in light, not in encoded value.* The first cut multiplied
   the sRGB-encoded colour, which for a headroom of 6 is 6^2.2 in light and clips the top of the
   image at the panel's peak. The shader now decodes to linear, scales, and re-encodes with the
   curve extended past 1.0; the presenter's pass inverts that exactly.
3. *A half-float canvas keeps what a UNORM canvas silently repaired.* The pass now maps NaN and
   Inf to 0 and clamps alpha to [0, 1]. No such values were ever observed on this canvas (the
   env-gated readback `JHV_EDR_DEBUG=1` scans every frame for them), but the cost is nothing
   and the failure would be a screen-wide artefact.

**SDR fidelity: passed, by a cleaner test than planned.** `extra/test/native/edr_fidelity_probe.m`
shows the same sRGB image on an untagged BGR10A2 layer and on a linear-tagged RGBA16Float layer
with the EOTF applied, in one window, captured in one screenshot: mean difference on lit pixels
0.48 counts, p99 2, max 19, median ratio 1.0000, with and without EDR engaged. The in-app
comparison in `extra/test/edr_sdr_fidelity.sh` is kept but its verdict is weaker: the state
restore and the fit-on-load race for the camera, so two runs of the same state land on
different views, and the miniview it falls back to draws that camera's field as a rectangle.
Its 5-count difference is that rectangle, not the pipeline.

**Export fidelity:** by construction and by unit test (`HdrGainCheck`): every capture pass
receives gain 1. No export was produced from this build; the movie-export code is untouched.

**Not settled:** the screen's headroom reading sometimes rises with the app at gain 1 and the
canvas verified at or below 1.0 for every frame. It is harmless (values at or below 1 display
as SDR either way) and it is not this app's content; it looks like the compositor keeping a
screen in EDR mode for a while after any EDR content has been shown.

**To run it:** `HFStudio (edr).app` on the Desktop builds and launches this worktree; `HFStudio
(dev).app` still builds `jhv-demo`. View menu: HDR Canvas, HDR Brightness.

## Revision, 2026-09-04 evening: three mappings

Gilly looked at the flat gain and did not love squinting at the whole image. The decision is
now three mappings, chosen in View > HDR Mapping, all applied on the brightest linear channel so
hue is kept, with a knee (View > HDR Knee: 25, 50, 75 % of white in linear light):

- **Linear**: every value times the gain (the original option A).
- **Hard knee**: identity up to the knee, then a straight line reaching the gain at white.
- **Soft knee** (default, knee 50 %): identity up to the knee, then a curve that leaves the knee
  at slope 1 and reaches the gain at white: highlight roll-up, no visible break.

`extra/test/hdr_curve_check.py` pins the properties (identity below the knee, headroom at
white, continuity, slope 1 for soft, monotonic). Measured on the AIA 171 frame with the
headroom at 6.2: linear puts 158,411 canvas pixels above white, soft knee 4,583.

## Revision 2, 2026-09-04, after Gilly looked

Blown out, oversaturated, unnatural. Three causes, all in the design rather than the pipeline:

1. The knee was judged on the colour's brightness. A table that lives near white (PUNCH's
   tan) put most of its field over the knee, so most of the image went over white. The knee
   is now on the **data value** that indexed the colour table: only the brightest data expands,
   whatever colour the table gave it. Knee stops: top 50, 25 (default), 10 % of the data.
2. Saturated colours at 6x are neon, not bright. The soft knee now **rolls to white**: as the
   expansion climbs, the colour blends toward a neutral of the same luminance, fully neutral at
   the top of the data. Linear and hard knee keep the table's colour.
3. "Auto = the panel's full headroom" is a demo setting. Brightness is now in photographic
   stops, default **+1 stop (2x)**, and a fixed stop never exceeds what the screen offers
   (past the headroom the compositor clips to peak white, which was part of the blown look).
   "Display maximum" is still there for anyone who wants the demo.

Measured at the new defaults on the AIA 171 frame: gain resolves to 2.0, 30 canvas pixels
above white. Screenshots cannot show any of this; the acceptance test remains Gilly's eyes.
