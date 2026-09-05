# Scope: the velocity filter's output on the GPU

Written 2026-09-05, after the CPU pass took the filter from 114 s to 17.8 s on a 245-frame
PUNCH mosaic movie (4096 x 4096, radial pass 100 to 500 km/s, measured end to end over SAMP
against the same layer both times).

## What is left to win

Measured on this machine, per frame at 4096 x 4096, after the parallel pass:

| stage | now | under this proposal |
|---|---|---|
| back-projection to each frame's grid | ~10 s over the movie | **gone** |
| packing to half float | ~1.7 s | **gone** |
| the NaN mask copy | ~1 s | **gone** |
| reading frames, LUT to physical | ~1.5 s | unchanged |
| resample into the cube | 1.4 s | unchanged |
| FFT | 0.2 s | unchanged |
| output held in memory | 8.2 GB off-heap | **268 MB on the GPU** |

So the filter would land somewhere around 5 s, and the 8.2 GB that forced the
own-your-own-frames fix on 2026-09-05 stops existing. Frame scrubbing after a filter
becomes free, because there are no frames to serve: the shader samples the cube.

The FFT stays on the CPU. It is 0.2 s of a 17.8 s run, and GL ES 3.0 has no compute
shaders, so moving it would mean fragment-shader ping-pong for no measurable gain.

## The idea

The back-projection is not really a computation. For each output pixel it works out (r, phi)
and reads the cube with bilinear weights. That is what a texture unit does in hardware, for
free, as its entire reason for existing. So instead of computing 245 frames and uploading
each one, upload the filtered cube once and let the fragment shader do the lookup while it
draws.

## Why this is smaller than it sounds

There is exactly one seam. Every projection samples the image through one function:

```glsl
// resources/glsl/solarCommon.frag:148
float fetch(const sampler2D tex, const vec2 coord, const vec2 bright) {
    return texture(tex, coord).r * bright.y + bright.x;
}
```

`getColor` calls it, and all seven `solar*.frag` shaders call `getColor`. A cube-sampling
variant is a change at that one function plus a handful of uniforms, not a change to seven
projections. Difference mode falls out for free: the previous frame is another `t`.

The two heavy consumers turn out not to be consumers at all. **EXR export already renders a
GPU pass** (`ExrCapture` calls `grabber.renderPass(layer, GLImage.Capture.DATA)`) and reads
the framebuffer, touching the CPU buffer only for its `PhysicalScale`. **Movie recording**
captures the canvas through `GLGrab` the same way. Both would follow the shader without
being told.

## What actually reads pixels on the CPU, and what happens to it

1. **Per-frame filters (RHEF, MGN, WOW).** The real obstacle. They are rank or neighbourhood
   operations over a whole frame, applied at decode. A shader sampling a cube has no frame to
   rank. Options, in order of preference: (a) refuse the combination while a sequence filter
   is on, which the row already knows how to express; (b) keep the CPU path as a fallback that
   materialises frames only when a per-frame filter is also selected; (c) port RHEF to a
   GPU histogram pass, which is its own project. Note Gilly was running RHEF on top of a
   sequence filter as recently as this morning, so (a) is a real loss and (b) is probably the
   answer.
2. **The value under the pointer.** Reads the decoded frame. Would sample the cube on the CPU
   instead: one pixel, the same arithmetic `toCartesian` does, microseconds.
3. **The measured bit depth in the layer readout.** Cosmetic. Report the cube's depth, or drop
   the line while a filter is on.

Nothing else in `src` reads an image layer's pixels.

## The texture shape

A 3D texture is the obvious representation and is the wrong one: GL ES 3.0 only guarantees
`GL_MAX_3D_TEXTURE_SIZE` of 256, and the cube is 1024 x 512 x 256.

Use a **2D array texture**: `nT` layers of `nR x nPhi`, internal format `R16F`. ES 3.0
guarantees 2048 for `GL_MAX_TEXTURE_SIZE` and 256 for `GL_MAX_ARRAY_TEXTURE_LAYERS`, so the
default grid fits on the guaranteed floor exactly, with nothing to spare on the time axis.
Query both at startup and shrink the grid the way the job already shrinks it against the heap
budget. Hardware bilinear covers (r, phi) within a layer; the interpolation in t is two
samples and a mix, which is what `toCartesian` does today.

`R16F` is filterable in ES 3.0 core, so no extension is needed for the sampling itself.

## Phases

1. **Cube upload and a cube-sampling `getColor`.** Behind a flag, PASS mode only, no
   per-frame filter, one projection (Ortho). Compare against the CPU output pixel by pixel:
   they should agree to half-float precision, and a check should say so rather than an eye.
2. **The remaining projections and difference mode.** Mostly proving that the one seam really
   was the only seam.
3. **The CPU consumers.** The pointer readout off the cube; the decision on per-frame filters
   made explicit in the UI rather than by silent disagreement.
4. **The fallback.** Keep the CPU path, choose between them on whether a per-frame filter is
   selected and whether the grid fits the queried limits. It is also the thing to fall back to
   when a driver refuses the array texture.
5. **Retire the frame array** in `ComputedView` for the GPU path, which is where the 8.2 GB
   goes away.

## Risks worth stating before starting

- **Sharpen** samples neighbours with a pixel-sized offset. In polar space that offset is not
  isotropic: a step in phi is a different distance at 12 R_sun than at 32. Either compute the
  offsets from the local Jacobian or disable sharpen while sampling a cube, and say which.
- **The occulter and the mask.** `getColor` discards on the mask texture, which is in image
  space. The cube covers only the annulus rIn to rOut, so everything outside it has to keep
  coming from the original frame: the shader needs both bound, and a rule for the seam.
- **Half float on the GPU.** The cube is float on the CPU. Storing it as R16F costs the same
  three significant digits the frames already cost, so nothing new, but the PASS amplitude
  scaling has to happen before the upload rather than after.
- **A second sequence filter on top of the first** currently works because `ComputedView`
  hands frames to the next one. It would stop working, or would need the CPU fallback.
- **This is macOS-first by accident.** The ANGLE-on-Metal path is what gets tested here;
  the Linux and Windows launchers ship untried, and this would be another thing untried on
  them.

## Recommendation

Worth doing, in this order, and not before the following two questions are answered:

1. **Is RHEF-on-top of a sequence filter something you use, or something you tried?** If you
   use it, phase 3 becomes phase 1, and the CPU path stays forever as the chaining path. That
   changes this from a replacement into a second path, which is a materially bigger job.
2. **Is 5 s enough of a win over 17.8 s to be worth a render-path change?** The memory is the
   better argument: 268 MB against 8.2 GB is what decides whether you can hold two filtered
   PUNCH movies at once, and today you cannot hold one comfortably.

If the answer to the second is no, the honest alternative is to stop here. The filter is 6.4x
faster than it was this morning and nothing about that needed a GPU.
