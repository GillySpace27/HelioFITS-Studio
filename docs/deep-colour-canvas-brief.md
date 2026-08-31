# Brief: get JHelioviewer's on-screen canvas past 8 bits per channel

## The task

The visible canvas quantizes every rendered frame to 256 levels per channel. Everything upstream
of it carries more: FITS layers decode to 16-bit half-float, the RHEF/MGN/WOW filters output
half-float, the colour table is sampled with linear interpolation so the ramp reaching the
framebuffer is continuous, and the movie export already renders into an RGBA16F target. The screen
is the one place the precision is thrown away, and it is the place a scientist looks while deciding
what is real.

Your job is to break that wall on macOS, or to establish with evidence that it cannot be broken and
say exactly what blocks it. Do not accept "ANGLE only offers 8-bit configs" as the end of the
investigation; that is where this brief starts, not where it stops.

## What has already been measured, on an M4 Max under macOS

Do not re-derive these. Do re-run them if you change the stack.

**1. The EGL window surface is 8/8/8/8.** Logged at every startup:

```
ANGLE EGL config: backend=Metal rgba=8/8/8/8 depth=24 stencil=0 sampleBuffers=0 samples=0
  (deep colour asked for, not offered)
```

**2. Asking for 10 bits changes nothing.** `AngleRenderer.chooseConfig` now walks
`COLOR_PREFERENCES = {10, 8}`, requesting `RGB10_A2` before `RGBA8`. It falls straight through. The
request costs one extra `eglChooseConfig` and is kept as a record.

**3. ANGLE offers no deep or float configs at all here.** `extra/test/EglConfigProbe.java`
enumerates every config on every backend. Run it with:

```bash
java --enable-native-access=ALL-UNNAMED -cp "extra/test-classes:bin:resources:$(find lib -name '*.jar' | tr '\n' ':')" org.helioviewer.jhv.opengl.angle.EglConfigProbe
```

Result today:

```
=== Metal (EGL 1.5) ===
  10 configs, distinct shapes:
    rgba= 8/ 8/ 8/ 8 fixed  window=true pbuffer=true
=== OpenGL (EGL 1.5) ===
  1 configs, distinct shapes:
    rgba= 8/ 8/ 8/ 8 fixed  window=true pbuffer=true
=== Vulkan: eglInitialize failed
```

`EGL_EXT_pixel_format_float` is advertised on both backends and yet no float config is enumerated.
Worth one probe of its own: the extension string may be inherited from ANGLE's frontend while the
Metal backend exposes nothing to back it.

**4. Two ANGLE extensions are present that route around the config list entirely.** From the same
probe, Metal backend:

```
EGL_ANGLE_metal_texture_client_buffer
EGL_ANGLE_iosurface_client_buffer
EGL_ANGLE_metal_shared_event_sync
EGL_ANGLE_metal_commands_scheduled_sync
EGL_ANGLE_device_metal is NOT listed; check whether it appears as a client extension
```

These are the most promising lead in this document. See "Approach A".

**5. RGBA16F is already proven colour-renderable on this exact stack.**
`GLFrameCapture` allocates `GL.RGBA16F` colour attachments for high-bit-depth movie export, checks
framebuffer completeness, and works. So the limitation is presentation, not rendering.

**6. The export path does NOT go through the 8-bit canvas.** `GLGrab.renderFrame` binds the capture
FBO and re-renders the scene into it, so a 16-bit export is a fresh 16-bit render, not a readback of
the screen. The "16-bit data displayed at 8 then written to 16" worry applies to what you watch,
not to what you ship. Verify this yourself before relying on it.

**7. The app owns its own CAMetalLayer, and hardcodes it to 8-bit.**
`native/macos/jhv_metal_host.m:37`:

```objc
metalLayer.pixelFormat = MTLPixelFormatBGRA8Unorm;
metalLayer.framebufferOnly = NO;
```

This layer is created by `MacAngleBridge.create` and its address is handed to
`eglCreateWindowSurface` as the native window. `framebufferOnly = NO` is already set, which means
the drawable texture is readable and writable rather than presentation-only.

## Code map

| What | Where |
| --- | --- |
| EGL init, config choice, context, surface, `eglSwapBuffers` | `src/org/helioviewer/jhv/opengl/angle/AngleRenderer.java` |
| ANGLE dylib extraction and LWJGL wiring | `src/org/helioviewer/jhv/opengl/angle/AngleLibraries.java` |
| Java to Objective-C bridge (FFM downcalls) | `src/org/helioviewer/jhv/opengl/angle/MacAngleBridge.java` |
| The CAMetalLayer itself | `native/macos/jhv_metal_host.m`, built by `ant build-metal-host` |
| AWT canvas that owns the layer and the renderer | `src/org/helioviewer/jhv/opengl/AngleCanvas.java` |
| Scene render, straight to framebuffer 0 | `GLRenderer.display()` / `renderScene()` |
| Proof that RGBA16F FBOs work here | `src/org/helioviewer/jhv/opengl/GLFrameCapture.java` |
| The dither that exists because of the 8-bit screen | `resources/glsl/solarCommon.frag`, `getColor()` |
| What the dither can and cannot do, measured | `extra/test/DitherCheck.java` |
| EGL config and extension enumeration | `extra/test/EglConfigProbe.java` |

The app is GLES 3.0 through ANGLE. There is no JOGL, no AWT GLCanvas, no direct OpenGL.

## Approaches, in the order worth trying

### A. Render into a Metal texture, present it yourself (most promising)

`EGL_ANGLE_metal_texture_client_buffer` lets an EGLSurface be created from an existing `MTLTexture`
via `eglCreatePbufferFromClientBuffer` with `EGL_METAL_TEXTURE_ANGLE`. The texture's pixel format is
yours to choose, and `MTLPixelFormatRGBA16Float` and `MTLPixelFormatBGR10A2Unorm` are both valid
CAMetalLayer formats.

Sketch:

1. In `jhv_metal_host.m`, set `metalLayer.pixelFormat = MTLPixelFormatRGBA16Float`, set
   `metalLayer.wantsExtendedDynamicRangeContent = YES`, and give it an extended-linear colorspace
   (`CGColorSpaceCreateWithName(kCGColorSpaceExtendedLinearSRGB)` or the display's own). Expose new
   entry points to hand back the current drawable's `MTLTexture`.
2. In `AngleRenderer`, replace the window surface with a pbuffer created from that drawable texture,
   through `eglCreatePbufferFromClientBuffer`.
3. Render as now. Present by committing the drawable in the native host rather than by
   `eglSwapBuffers`. `EGL_ANGLE_metal_shared_event_sync` or
   `EGL_ANGLE_metal_commands_scheduled_sync` is how you make the GLES work visible to Metal before
   the present; do not assume an implicit barrier.

Unknowns to settle first, cheaply, before writing any of it:

- Does ANGLE accept a `RGBA16Float` MTLTexture as a client buffer, or only 8-bit formats? Its
  validation code has a format allowlist. Test with a throwaway texture before touching the host.
- Does a per-frame `nextDrawable` texture work as a client buffer, or must you render into your own
  persistent texture and blit into the drawable in Metal? The second is more likely to work and is
  only one extra full-screen blit.

### B. IOSurface instead of a Metal texture

`EGL_ANGLE_iosurface_client_buffer` is the same idea through IOSurface, which supports 10-bit and
half-float formats and is what the extension was written for. Slightly more plumbing, possibly
better-tested inside ANGLE because it is the macOS video interop path. Try this if A hits a format
allowlist.

### C. Render at 16F internally, dither only at the final present

Even if presentation stays 8-bit, the scene can be rendered into an RGBA16F FBO and blitted to the
window surface at the very end. This does not add display levels, so on its own it is not a fix, but
it is cheap, it is proven to work (`GLFrameCapture` does it), and it makes two real differences:
compositing and blending stop accumulating 8-bit error, and the final quantization becomes a single
controlled step where a proper ordered or blue-noise dither can be applied once instead of the
per-layer noise in `getColor`. Treat this as the fallback deliverable, not the goal.

### D. Drop ANGLE for a native Metal renderer

Complete rewrite of the render path. Out of scope unless A, B and C all fail and the finding is
important enough to justify it. If you get here, say so and stop rather than starting it.

### E. Check whether a newer ANGLE offers deep configs

The dylibs are vendored. `AngleLibraries` extracts them from the resource directory. A newer ANGLE
build may enumerate `RGB10_A2` or float configs on Metal, in which case the ladder already in
`chooseConfig` picks them up with no further work. Cheap to test, worth doing early, but do not
count on it: shipping a new ANGLE has its own risk surface.

## Acceptance criteria

A change is only a success if all of these hold:

1. `ANGLE EGL config:` in the log reports more than 8 bits per channel, OR the presentation path no
   longer goes through an EGL window surface and a new log line states what the layer's pixel format
   actually is. A claim without a log line is not evidence.
2. A synthetic smooth gradient shows visibly fewer steps with the dither turned OFF
   (View, Dither Colour Banding) than it does today. That is the direct test: today the dither is
   the only thing hiding the screen's quantization, so a deeper canvas must make its absence
   unremarkable. Write the gradient as a test layer if there is not one.
3. Colours are unchanged in appearance. An extended-linear colorspace on an EDR layer will make
   everything look wrong if the shader keeps writing sRGB-encoded values into it. Whatever you do
   about colour management, state it explicitly; a picture that is deeper and also wrong is worse
   than the current one.
4. The existing checks still pass: `extra/test/*Check.java`, all of them, plus
   `python3 extra/test/validate_glsl_syntax.py`.
5. The 8-bit path still works, reachable without a rebuild. `display.deepColorCanvas=false` already
   exists as that switch; keep it meaningful.
6. Movie export is unaffected or better. It renders into its own FBO, so it should be untouched;
   confirm rather than assume.

## Traps

- **The AWT/Metal layer lifecycle is fragile.** `AngleCanvas` recreates the renderer when the native
  window handle changes, and `PresentationMode` tears the whole thing down and rebuilds it on
  another screen. Anything you add to the host must survive `destroy` and re-`create`.
- **Two contexts exist.** There is a pbuffer renderer as well as the window one
  (`AngleRenderer.pbuffer`, used by `AnglePbuffer`). Do not break it.
- **`Display.highBitDepthCapture` already exists** and gates the shader's dither during 16-bit
  export. If the screen becomes deep, that flag's meaning and the `skipDither` uniform it feeds both
  need revisiting; see `Display.skipDither()`.
- **Do not "fix" banding with a source-domain dither.** It has been tried and measured in this repo
  and it recovers nothing; see the class comment and printed numbers in `extra/test/DitherCheck.java`
  before spending an afternoon on it.
- **The mac host is built by `ant build-metal-host`,** which is a separate target from `compile`. It
  is invoked by `ant run`. If you edit the `.m` and only run `ant jar`, you will be testing the old
  dylib and drawing wrong conclusions.
- **Verify on the real display.** An M4 Max drives an XDR panel that can show more than 8 bits, but
  a screenshot is 8-bit and will not show you the difference. Judge by eye on the panel, and by the
  log for what was actually negotiated.

## What to report back

Whatever happens, the deliverable includes a plain statement of which of the five approaches were
tried, what each one returned, and the measured evidence. If the answer turns out to be that macOS
plus ANGLE cannot present more than 8 bits per channel without replacing the renderer, that is a
legitimate result, but it needs the probe output that proves it, not an inference from the config
list already in this brief.

## Outcome (2026-08-31)

The wall is broken. The canvas now renders into an RGB10_A2 IOSurface wrapped as an EGL pbuffer
and reaches the screen through a native Metal blit into a BGR10A2Unorm CAMetalLayer. Startup logs:

```
ANGLE EGL config: backend=Metal rgba=8/8/8/8 depth=24 stencil=0 sampleBuffers=0 samples=0 (config for context only; deep canvas attempt follows)
Deep-colour canvas: RGB10_A2 IOSurface pbuffer presented by Metal blit; CAMetalLayer pixelFormat=BGR10A2Unorm, colorspace unmanaged as before
```

What each approach returned:

- **A (Metal texture client buffer): does not exist as a surface path in this ANGLE.** In the
  vendored build (2.1.27045, 1c0f91aaa60a) `EGL_ANGLE_metal_texture_client_buffer` is EGLImage-only;
  `createPbufferFromClientBuffer` accepts `EGL_IOSURFACE_ANGLE` and nothing else. Confirmed in
  `DisplayMtl.mm` at that commit.
- **B (IOSurface client buffer): works, and is the shipped mechanism.** The pbuffer takes its format
  from the ATTRIBUTES, not the config, so the 8-bit config list is irrelevant. It can be made
  CURRENT as the draw surface (framebuffer 0 stays framebuffer 0). `IOSurfacePbufferProbe`
  measures the round trip: RGBA16F max error 7.3e-5, RGB10A2 2.4e-4, versus 1.57e-3 for 8-bit.
- **C (16F FBO + final blit): not needed;** B gives the deep default framebuffer directly.
- **D (native renderer): not needed.**
- **E (newer ANGLE): not tried;** B removed the reason.

Two traps found and handled:

- **The y-flip.** ANGLE's window surface flips GL's bottom-left rows during its own present; a raw
  blit does not, and the scene appeared upside down. Fixed at composite time with
  `layer.transform = CATransform3DMakeScale(1, -1, 1)`, undone when falling back to the window
  surface.
- **Untagged float layers are colour-managed.** The first build presented RGBA16F, and the
  compositor brightened everything: on identical scenes the legend bar differed from the 8-bit
  baseline by a mean of 40/255 counts (greys followed an sRGB-encode curve, saturated colours
  something gamut-shaped). Untagged UNORM passes through: with BGR10A2Unorm the same comparison
  gives max 2 counts, all within the dither the 8-bit run still had on. RGBA16F presentation
  needs real colour management and is left as follow-up; 10 bits matches the panel pipe anyway.

`display.deepColorCanvas=false` still selects the old 8-bit window surface (verified by log and by
the same screenshot comparison). The shader dither is skipped on the deep canvas
(`Display.deepCanvas`), for the same reason `highBitDepthCapture` skips it. Movie export is
untouched: it renders into its own FBO and rebinds framebuffer 0 after. The by-eye gradient check
on the XDR panel remains: `extra/test/make_gradient_fits.py` writes the test layer.
