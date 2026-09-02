# Plan: sequence filters (velocity band-pass, orbital notch, 3D noise gating)

## Context

Gilly asked for two things at once. First, Fourier filtering that selects coronal features by how
they move: a band-pass keeping only structure travelling at a chosen range of radial speed (CME
fronts, outflows), and the azimuthal counterpart, a notch removing patterns that turn in position
angle at the PUNCH spacecraft orbital rate (the stray-light and mosaic artefacts that move with
the orbit). Second, in parallel, DeForest's 3D noise gating (DeForest, C. E. 2017, ApJ 838, 155,
doi:10.3847/1538-4357/aa67f1), the adaptive relative of the first: a Fourier-domain gate on small
(x, y, t) neighbourhoods, open where a component rises above a noise level estimated from the
data, closed elsewhere.

All three are properties of the movie, not of a frame. JHV's filters (MGN, WOW, RHEF) are
single-frame decoders; these need every frame at once, an FFT, and a way to hand a computed
sequence back to the layer without losing the original. One shared core carries both features.

## What already exists

`ImageFilter` (`src/org/helioviewer/jhv/image/ImageFilter.java`) is one frame in, one frame out;
its `of(Type, Region, MetaData)` is the only geometry seam, and `FilterRHEF` the only radius-aware
code. There was no polar resampler, no FFT, and no math jar in `lib/`. `View` is an interface with
defaults whose frames arrive by push; `ManyView` holds the movie; `URIView` reads each FITS frame
at construction and keeps the unfiltered buffer in `ImageBufferCache`. `ImageLayer.setFixedRange`
is the one precedent for a layer-wide setting applied to every frame. **The constraint that
shaped the rest: `ImageLayer.replaceView` abolishes the view it replaces, and a decorator wraps
exactly that view.**

## The correction this plan is built around

The stored frames are not the data. They are half float, stretched (gamma, asinh, log), scaled
per frame, and possibly RHEF-ranked. A linear filter on that turns the stretch into harmonics and
the per-frame scaling into a brightness oscillation, and a shot-noise model (noise proportional
to the square root of intensity) is meaningless on a stretched value. Everything here runs in
physical units, on the `Type.None` buffers, whatever the per-frame combo says; installing a
sequence filter forces that combo to `None` and greys it.

Two more, found by the checks rather than by reading:

- A notch or gated output is an estimate of the original field and must come back re-encoded
  with the frame's own stretch and scale; a pass output is a signed fluctuation and is shown like
  difference mode (mid-grey is zero). Mixing the two is a picture that looks plausible and reads
  wrong on the legend.
- The noise gate cannot be applied to a raw coronagraph or EUV frame the way the paper writes it.
  The paper's examples were unsharp-masked (AIA) or zero-mean (HMI). On a frame with a mean and a
  gradient far above the noise, the Hanning window leaks them into the lowest Fourier components
  of every neighbourhood, the estimate across neighbourhoods calls those components "noise", the
  gate closes them wherever the local level is below three times the typical one, and the picture
  falls apart (residual 12.8 became 80.5 on the synthetic sequence). A box-mean background of
  side 2n + 1 per frame now bypasses the gate; the fluctuation about it is gated and the
  background added back. The shot-noise norm still uses the raw values, as Eq. 4 and 7 say.

## Conventions

Transform F(k, omega) = sum I(r, t) exp(-i (k r + omega t)). A feature f(r - v t) has power on
omega = -v k, so v = -omega / k; outward is v > 0. A pattern g(phi - Omega t) has power on
omega = -m Omega; prograde is Omega > 0, phi from the top of the buffer, anticlockwise on screen
(`PolarBasis.angle`). The band mask is 1 on [lo, hi] with raised-cosine transitions of 10 percent,
times a sign test; PASS multiplies by it, NOTCH by its complement; static (omega = 0) and uniform
(k = 0) modes are removed by PASS and kept by NOTCH. Tukey windows (alpha 0.25) in t and, for the
radial kind, in r; none in phi. The time grid is the median cadence; frames are interpolated onto
it and the result sampled back at each frame's own time. The noise gate never interpolates:
frame index is its time axis, because interpolation averages independent noise samples and
breaks the statistics Eq. 7 rests on. Its neighbourhoods are 16^3 or 8^3 at a stagger of n/4 with
the double Hanning window and the 1.5 per axis overlap normalisation (Eq. 15 to 17); the paper's
12^3 needs a mixed-radix transform this tree does not have. The assumed PUNCH orbital period is
labelled assumed in code and UI; the rate spectrum measured from the movie is the source of truth.

## Phases

### Phase 1: FFT  **[DONE 2026-09-01]**

Outcome: `image/fourier/FFT.java`, radix-2 in place on split float arrays, strided along any axis
of a packed array, 2D and 3D on top. Check: `FFTCheck`, whose single-bin exponential pins the sign
convention; a transform off by a conjugation round-trips perfectly and would silently swap inward
and outward.

### Phase 2: plumbing  **[DONE 2026-09-01]**

Outcome: `View.frameImage(int)` (synchronous unfiltered frame; `URIView` re-reads the file on a
cache miss, `ManyView` delegates, JPEG 2000 stays null); `PhysicalScale.forwardStretch` and
`toDisplay`; `FrameStack` (frames to physical with NaN for missing, and back with `packLike` or
`packSigned`); `SequenceParams` (sealed, typed JSON) and `SequenceJob`; `view/ComputedView`, the
decorator that serves computed frames from the cache and still holds the original;
`ImageLayer.setSequence` / `swapView` (no abolish), the `setView` hook that applies pending
params once the full movie is in, and the `sequence` JSON block beside `fixedRange`. Check:
`ComputedViewCheck` counts `abolish` calls on a stub view while the real radial job runs over its
frames: dispose never touches the wrapped view, abolish forwards exactly once.

### Phase 3: polar cube  **[DONE 2026-09-01]**

Outcome: `PolarCube`, bilinear both ways with NaN as missing, the time mean subtracted and put
back. Check: `PolarCubeCheck` on an asymmetric picture (a top blob at phi = 0, a left blob at
phi = pi/2, a NaN wedge). It found that the mean plane was sampled nearest-neighbour under a
bilinear fluctuation: a 2.9 percent ripple that every notched movie would have carried. Fixed;
the round trip is 0.12 percent.

### Phase 4: velocity filters  **[DONE 2026-09-01]**

Outcome: `FourierFilter` (mask, windows, per-slice 2D transforms, the rate spectrum) and
`FourierJob`. Check: `FourierFilterCheck` on cubes with known content. PASS 200 to 400 km/s
outward keeps 92 percent of a feature at +300 km/s and leaks 6 percent of one at -300; NOTCH
inverts that; the orbital notch removes a pattern turning once per 96 min to -0.7 percent while
a static m = 4 pattern keeps 100 percent, and a retrograde-only notch leaves the prograde
pattern alone. The rate spectrum peaks at 293 km/s and 97.5 min. The check also established
that the rate grid at wavenumber a is (radial span) / (time span) / a, so a feature's lowest
wavenumber is unresolvable against 0 and 2v: the readout states the resolvable range.

### Phase 5: noise gate  **[DONE 2026-09-02]**

Outcome: `NoiseGate` (window, mirror pad at the image edge only, background, estimator with a
bounded reservoir per component and per radial band, hard and Wiener gates, phase-ordered
parallel overlap-add) and `NoiseGateJob` (spatial tiles of 256 with a halo of 2n real pixels
over all frames, two passes, output written straight into off-heap buffers). The halo is 2n
because a neighbourhood touching an interior pixel reaches n beyond it and its background box
reaches n beyond that; with a halo of n the outer ring of neighbourhoods saw a clamped
background and every tile edge was a faint seam. The estimate samples only neighbourhoods
centred on real pixels: mirror padding is a fold, which is structure, and a tile's halo is also
its neighbour's, so each real neighbourhood counts once. The noise level is estimated in radial
bands about the Sun (eight by default) and interpolated in radius, which is what the paper calls
an obvious extension and what a coronagraph, darker and noisier outward, needs.

Check: `NoiseGateCheck`, eleven assertions. Gamma 0 reproduces the input to 6e-7 (the identity
that a wrong overlap constant or edge pad would fail while looking fine). On drifting blobs with
shot noise the estimated spectrum is flat to 5 percent, the residual against the truth drops
from 12.1 to 1.0, blob integrals hold to 0.1 percent, and the removed part correlates with the
truth at 0.004; the additive hard gate takes 20.0 to 1.4 and the Wiener gate to 7.1; the 2D
fallback on 8 frames takes 12.1 to 1.7; two haloed tiles equal the whole to 2e-7; with noise
growing threefold outward the banded estimate tracks it (1.98 against 1.9 expected between two
band centres) and banded gating beats one level (residual 1.5 against 3.4).

Two lessons from getting there. The first version of this check passed on a 96-pixel frame
where a third of the estimate's neighbourhoods held a blob: the median at low wavenumber was
signal-dominated and the gate over-closed, and it only looked fine because 40 percent of the
samples were mirrored background. The paper's condition, that most samples be noise-dominated
at every component, is real; the frame is now 160 pixels and the percentile control exists for
structured data. And annuli are the right unit for the noise level but the wrong unit for the
neighbourhoods: resampling to polar correlates independent noise samples, the same reason the
gate never interpolates in time.

### Phase 6: UI  **[DONE 2026-09-02]**

Outcome: `layers/filters/SequencePanel`, one row after the filter combo: kind combo (Off,
Radial velocity, Angular velocity, Noise gate), a popup whose card follows the kind, a readout
(frames, cadence, largest gap, resolvable range or neighbourhood size, memory), Apply with the
circular progress bar that cancels on a click, and a Spectrum dialog drawing power against rate
with the band shaded and a click setting its centre. The noise gate card has "Noise level varies
with radius" on by default. The per-frame filter combo stays live while a sequence is installed:
`ComputedView` applies it to the computed frames off the EDT the way `URIView` applies it to a
decoded one, so RHEF can follow a noise gate or a notch. `jhv.sequence.set` over SAMP takes the
JSON or "off" for a named layer or the active one.

### Phase 7: live runs  **[DONE 2026-09-02, LASCO; PUNCH pending]**

Outcome, on a second JHV over SAMP with the LASCO C2 + C3 state (104 frames of 1024 x 1024,
median cadence 12 min): radial PASS 200 to 800 km/s outward computed in 3.2 s; the exported
frame's `LASCO_C2.Y` sits at 0.50 inside the footprint on a symmetric scale of plus or minus
1.28 DN/s, a fluctuation field (standard deviation 0.146) against an original mean of 0.94, and
the header carries the `sequence` block. Additive hard noise gate at gamma 3, n 16: 59.6 s (16
tiles: frames 0.2 s, estimate 5.6 s, gate 51.1 s, write 2.5 s), the gated frame differing from
the original at 46 percent of footprint pixels by 0.0004 RMS of range, which is what a smooth,
already background-subtracted product should give. Off: the block is gone, the ungated original
is back, and the log gained no network lines.

Two defects the run exposed, both fixed: the physical-value table was rebuilt with a `pow` per
entry for every tile, which was 340 of the first run's 398 s (now cached per scale); and a
locked timeline re-issues every layer's query whenever its selection is nudged, which replaced
the view and cancelled any running job. An identical re-query is now a no-op in
`ImageLayer.load(FitsRequest)`, which also spares a locked session a reload on every nudge.

Rerun after the halo and the radial bands (2026-09-02 16:05): radial pass 3.5 s; noise gate with
eight radial bands 75.4 s (frames 1.0 s, estimate 7.0 s, gate 62.9 s, write 2.5 s; the halo of
2n makes a tile 320 pixels wide instead of 288, which is the extra quarter); off restores the
original with no network lines. 47 checks green.

Pending: the PUNCH orbital notch on a mosaic movie (the archive did not serve one in time), a
session save and restore with a filter on, and RHEF on top of a gated movie seen on screen (the
code path is `URIView`'s, but no SAMP command sets the per-frame filter).

## Files

`image/fourier/{FFT, FrameStack, SequenceParams, SequenceJob, FourierParams, PolarCube,
FourierFilter, FourierJob, NoiseGateParams, NoiseGate, NoiseGateJob}.java`,
`view/ComputedView.java`, `layers/filters/SequencePanel.java`;
modified `view/View.java`, `view/uri/URIView.java`, `view/ManyView.java`,
`image/ImageBuffer.java`, `view/uri/FITSImage.java`, `layers/ImageLayer.java`,
`layers/selector/ImageLayerRenderingPanel.java`, `layers/filters/ImageFilterPanel.java`,
`movie/ExrCapture.java`, `io/samp/ViewHandlers.java`, `app/Commands.java`;
checks `extra/test/{FFTCheck, PolarCubeCheck, FourierFilterCheck, NoiseGateCheck,
ComputedViewCheck}.java`.

## Verification

1. `ant clean jar` (static finals inline; always clean), then
   `javac -cp "JHelioviewer.jar" -d extra/test-classes extra/test/*Check.java` and run every
   check with `-cp "JHelioviewer.jar:extra/test-classes"` (47 today, all green).
2. A second JHV on the LASCO C2 + C3 state (wait for the log to go quiet, then 90 s for the
   decode), `jhv.sequence.set` with the radial pass 200 to 800 km/s outward, an EXR loop
   recorded, frame 50 inspected: `LASCO_C2.Y` sits around 0.5 on a symmetric physical scale, the
   `.meta` carries the `sequence` block. Then the additive noise gate, the same. Then `off`: the
   `sequence` block is gone and no network lines were added to the log (a reload would mean the
   abolish trap was hit).
3. PUNCH mosaic movie: the Spectrum dialog shows the orbital ridge; the notch set from it removes
   the artefact and keeps the static seam pattern. Pending a PUNCH load that the archive serves.
4. Session save and restore with a filter on: the job re-runs once the full movie is installed.
