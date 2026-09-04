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
