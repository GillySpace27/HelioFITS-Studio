# Plan: high-bit-depth and dome-format output

## Context

Two requests, one pipeline. Gilly wants a 2:1 equirectangular aspect lock in Playback and
Recording, with the aspect and the long-side resolution as separate controls and the short side
derived, plus an on-canvas overlay showing what will actually be captured. Thor Metzinger
(Fiske Planetarium) additionally asks for 16/32-bit PNG, TIFF and EXR framestacks, multi-layer
EXR with one dataset per layer, and 8K x 4K equirectangular for a 4K dome. His fourth request,
an undocked floating display window, already shipped as presentation mode.

Everything here funnels through one capture path, so the order below matters more than usual:
the bit-depth work unblocks every format, and the aspect work is independent of it.

## What already exists

`GLGrab.renderFrame` (`src/org/helioviewer/jhv/opengl/GLGrab.java`) already renders offscreen at
an arbitrary size: it swaps `Display.setGLSize` and `reshapeAll`, renders into an FBO through
`GLFrameCapture`, reads the pixels back, and restores the viewport in a `finally`. **Arbitrary
output dimensions are therefore already supported.** The aspect work below is user interface and
framing; it does not need new capture plumbing.

`ViewState.RecordingSize` is a fixed enum of six sizes (`ViewState.java`), surfaced as a single
combo box at `MoviePanel.java:185`. `ViewState.Size` already carries an `internal` flag
distinguishing an explicit size from "whatever the window happens to be".

The constraint that shapes the rest: **`GLFrameCapture` reads back 8-bit RGBA.** Writing that
into a 32-bit float file yields a 32-bit container holding 8 bits of information.

## The correction this plan is built around

Thor writes that "Color Space is 32bit float so all science information remains untouched". That
is not true of a rendered frame, and the distinction decides what is worth building. By the time
a pixel reaches the framebuffer it has been through the colour table, the brightness and gamma
controls, the RHEF or MGN filter, the radial warp, and clipping. A float EXR of that preserves
the *rendered* value at high precision; it does not recover the photometric quantity.

Float output is still worth having, for reasons that are real but different: no banding in the
faint gradients that dominate coronagraph imagery, headroom for compositing, and no clipping at
pure white or black. Those are exactly the wins that matter for dome work.

If science-preserving output is genuinely wanted, that is a separate feature: emit the calibrated
values from *before* the display pipeline as their own channel alongside the rendered RGB. Phase
5 sketches it. It should be named honestly as a data channel rather than implied by the bit
depth, or someone will eventually publish a number read off a rendered pixel.

## Phases

### Phase 1 — Float capture (unblocks everything else)  **[NOT STARTED]**

Render the offscreen capture into a floating-point FBO (`RGBA16F`, with `RGBA32F` behind a
setting) and read that back, rather than 8-bit RGBA. Contained to `GLFrameCapture` and
`GLGrab.renderFrame`; the existing 8-bit path stays the default for video export, which is
8-bit downstream anyway.

Check: capture a smooth synthetic gradient at 8-bit and at 16F and confirm the banding present in
the first is absent in the second. A format that merely *claims* more bits is the failure mode
here, and it looks identical to success unless the gradient is examined.

### Phase 2 — Aspect lock and the output overlay  **[DONE 2026-08-28]**

Replace the single size combo with two controls plus a derived readout:

- **Aspect**: 2:1 (equirectangular), 16:9, 1:1, 4:3, custom
- **Long side**: resolution in pixels
- **Short side**: derived from the two, shown read-only

`RecordingSize` becomes `{aspect, longSide}`, keeping the six current enum values as presets so
existing sessions still load. This makes "2:1 at 8K" a single decision rather than a lookup, and
makes an inconsistent width/height pair unrepresentable.

The overlay is a checkbox drawing the capture region on the canvas: the output aspect inscribed
in the viewport, the region outside it dimmed, a bright border with corner ticks, and a live
`8192 x 4096` label. Draw it from `renderFullFloat`, which runs in every projection and after
everything else, the same hook `TimestampLayer` uses. **It must hide itself during capture**, or
it lands in the output; that is the one bug this feature is likely to ship with, so test it by
recording a frame with the overlay on and inspecting the file rather than the screen.

Note that inscribing 2:1 in a taller window leaves the captured region much smaller than the
viewport, so composition happens in a letterboxed strip. Default to inscribe-and-overlay, and
add a "fit window to output aspect" button for when that is too cramped. Reshaping the window
outright would fight presentation mode, where the projector window and the presenter window have
deliberately different shapes.

Guard the long side against `GL.maxTextureSize`, which `GL.initInfo` already records, and warn
before the capture fails rather than after.

### Phase 3 — Lossless still formats

16-bit PNG and 16/32-bit TIFF framestacks. Neither needs a new dependency: `ImageIO` handles
16-bit PNG, and TIFF support is in the JDK. Wire them into the existing recording modes as
alternatives to the video encoder.

### Phase 4 — EXR

There is no EXR writer in the tree and no pure-Java one among the dependencies. Two options:

1. JNI to OpenEXR. Full format support, but it adds a native library to a notarized macOS
   bundle, and `deploy_release.sh` already signs every bundled Mach-O individually. Real
   packaging cost, and a new failure mode in the release.
2. A minimal writer covering the subset needed: scanline, half or float, uncompressed or ZIP.
   A few hundred lines, no new native code, no signing consequences.

Recommend (2). The format's complexity lives in features we do not need.

Multi-layer EXR, one image layer per EXR layer, requires rendering each layer to its own buffer
instead of compositing them, so N passes per frame. It fits naturally once float capture exists,
and it is the piece Thor is most specifically asking for.

### Phase 5 — Science data channel (optional, and separate)

Emit calibrated values from before the display pipeline as an additional float channel, so a
frame carries both what it looked like and what it measured. This is the only part of the work
that could honestly be called science-preserving. Worth doing; worth naming carefully.

## Files

`opengl/GLFrameCapture.java`, `opengl/GLGrab.java` (phase 1); `app/state/ViewState.java`,
`gui/component/MoviePanel.java`, a new overlay in `layers/` or `annotation/` (phase 2);
`export/` (phases 3 and 4).

## Verification

1. `ant clean jar build-metal-host`. Always clean: several of these are `static final` constants,
   which javac inlines into dependent classes, and an incremental build gives a stale mismatch
   that surfaces as an `ArrayIndexOutOfBoundsException` at startup.
2. The gradient test in phase 1. Do not accept a bit depth claim without it.
3. Record one frame with the overlay enabled and inspect the written file, not the screen.
4. Confirm a 2:1 capture is genuinely 2:1 at several window aspect ratios, including a window
   narrower than 2:1.
5. Round-trip a session through save and reload and confirm the aspect and long side come back.
