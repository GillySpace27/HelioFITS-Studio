# Parameter animation: automation lanes in the Timelines panel

Written 2026-09-08 against the code, before touching it. Gilly's ask: right-click a parameter
control, choose "Animate", and that parameter appears as an editable lane in the Timelines panel
with draggable keys, the way a DAW automation lane works. Nothing here is built; this is the shape
of it, so the design can be argued with before it costs a build.

**Status, 2026-09-08 (after the spec was written): phases 1 to 4 are built and verified.** The
tracks, the registry, the per-frame applier, the layer ids, the session's `automation` object and
the read-only lane all exist and were exercised end to end against a real session (three tracks,
PUNCH + LASCO + SUVI layers). Phases 5 to 7 are not built. Two things changed under contact with
the code and are marked inline below: the registry resolves per frame instead of taking
registrations, and the lane does not print a live value. Section 11 records both.

## 1. What the feature is

Every knob in the application is a constant today: you set the opacity, the warp lambda, the HDR
gain, and it holds that value for the whole movie. This makes it a function of time instead. You
right-click a slider, choose Animate, and a lane appears in the Timelines panel beside the coverage
track: a curve across the same time axis the movie already runs on, with points on it you can drag.
The renderer reads the curve at the playhead on every frame, so the parameter moves as the movie
plays, and the recorded file contains exactly what the screen showed. It is the audio automation
lane, with the video world's word for the points. The first group of parameters is the image layer
look (opacity, blend, sharpen, levels, contrast, enhance, upsilon), the HDR controls (gain, knee,
in-range), the warp geometry (Box-Cox lambda, outer radius edge, disk scale), and the overlays
(grid line width and opacity, spiral speed). Playback rate is asked for too, and it is a different
animal; section 4 is about why.

## 2. The data model

A key is a time, a value and the interpolation of the segment leaving it:

```java
record Key(long time, double value, Interp interp) {}
enum Interp { HOLD, LINEAR, SMOOTH }
```

`time` is data time in milliseconds, the same clock `TimeAxis` and `Player` use. `value` is the
parameter in its own physical units, never in slider ticks: the Edge slider's position maps to a
radius through `radius = 2 * (full/2)^(t/1000)` where `full` is `ImageLayers.getLargestRadialSize()`
(ToolBar.java:1198-1200), so a stored tick means a different radius as soon as a layer is added or
removed. Storing radii in solar radii is stable; storing ticks is not. `HOLD` is a step, `LINEAR` a
straight segment, `SMOOTH` a smoothstep ease that is flat at both keys. No Bezier handles in
version 1: two-handle curves need their own gesture vocabulary and are the single thing most likely
to turn a week into a month.

A track is a parameter key, a sorted list of keys, and an enabled flag:

```java
record Track(String paramKey, List<Key> keys, boolean enabled) {}
```

Evaluation clamps at both ends: before the first key the first key's value, after the last the
last key's. No extrapolation, because extrapolating an opacity past a movie's end is only a way to
arrive at a black screen.

**Binding.** A track names its parameter with a stable string, and a registry maps that string to a
cheap getter and setter:

```
display.warpLambda        display.warpOuterRadius   display.diskScale
hdr.gain                  hdr.knee                  hdr.inRange
layer:<layerId>/opacity   layer:<layerId>/sharpen   layer:<layerId>/upsilonLow  ...
grid:<layerId>/lineScale  grid:<layerId>/alpha      viewpoint:<layerId>/spiralSpeed
player.rate
```

`<layerId>` has to be new. There is no stable layer identity today: `ImageLayer.getName()` returns
"Loading..." until the data arrives (ImageLayer.java:621-623), two AIA 171 layers have the same
name, and list position is not stable either, because a restored session loads its image layers
asynchronously (`Task.submit(new ImageLayers.WaitUntilLoaded(...))`, State.java:325-328) and then
prunes the ones that failed (State.java:342). So `AbstractLayer` gains a `String id`, generated
once, written in `State.layer2json` (State.java:158-176) and read back in the constructor.

The registry is what makes the asynchronous arrival a non-problem. Tracks are loaded first and hold
only their key strings. A layer registers its parameters when its view is ready and unregisters when
it is removed. The applier looks the key up on every frame; an unresolved key does nothing that
frame and costs a hash lookup. Nothing has to be ordered, nothing has to be retried, and a track
whose layer never loads is still written back out on the next save rather than being quietly
dropped.

The registry is also where every trap in section 8 gets neutralised once. Its contract is that the
registered setter is the cheap one: no properties-file write, no `DisplayController.display()`, no
fan-out to other selected layers.

## 3. The evaluation model

Apply every track at the top of `GLRenderer.display(Position)` (GLRenderer.java:139), before
`Layers.prerender()` (line 146) and before `mapView = createMapView(...)` (line 157), evaluated at
`Player.getTime().milli`.

Before `createMapView` because the geometry parameters are read there and by the warp mesh in the
same call; applied later they would be one frame stale, which is exactly the class of bug that is
invisible until someone steps through a recording.

At the render entry point rather than on a `Player` time listener because a frame is also drawn when
nothing about time changed: a camera move, a resize, a window uncover. On a time listener the
parameters between two time changes would be whatever they were last left at, which is right by
accident and not by construction. Applying on every frame is idempotent and costs a few dozen
double interpolations.

And, decisively, because the export is grabbed from inside that same method:
`ExportMovie.handleMovieExport()` is the last thing `GLRenderer.display` does (GLRenderer.java:167-168).
There is no second code path to keep in step. The pixels encoded are the pixels the tracks produced,
in the same call, from one evaluation.

That is not a convenience. In this application the picture is the claim, and every existing capture
path is built on the promise that the file is the screen: `GLGrab` renders the same scene the canvas
does, and `HdrTransfer.capture` is set for exactly the duration of a recording (ExportMovie.java:203)
so the encode matches what was displayed. A parameter animation evaluated on a different clock for
export would produce a movie that is a different picture from the one on screen, with no way to say
which of the two is the honest one. Anything that forces the export onto its own schedule (see
section 4) is a reason to reject that part of the feature, not a reason to relax this rule.

Within a frame, tracks are applied in sorted key order, so two tracks that reach the same underlying
state resolve the same way every time rather than by hash iteration order.

## 4. The frame-rate track, honestly

This one is Gilly's own addition and it is not like the others. The rest set a value the renderer
reads; this one sets the rate at which the renderer is asked for the next value. Three facts decide
what it can and cannot be.

**There are two speed regimes, and they are not two settings of one thing.**
`ViewState.PlaybackSpeedUnit` (ViewState.java:38-52) is either `FRAMES_PER_SECOND` or one of solar
minutes, hours or days per second. In the relative regime `Player.setDesiredRelativeSpeed`
(Player.java:403-407) sets a Swing timer delay of `1000/fps` and each tick advances exactly one
frame, ignoring what the frames' timestamps are. In the absolute regime
`Player.setDesiredAbsoluteSpeed` (Player.java:409-413) pins the tick at 30 Hz and advances time by
`deltaT = 1000 / FPS_ABSOLUTE * sec` milliseconds per tick.

**A recording is not paced by the clock at all.** `ExportMovie.startRecording` takes the fps once
(ExportMovie.java:137, 200) and writes it into the file header. During the recording `Player.syncTime`
returns immediately while a grab is outstanding (Player.java:335-336) and is released by
`Player.grabDone()` from the export path (ExportMovie.java:114). So the file gets exactly one output
frame per Player frame, at a constant header rate, however fast or slow the wall clock was.

**Therefore, in Frames/sec mode a rate curve does nothing to the recording.** It slows the screen
and leaves the file untouched: the exact "the export is not the screen" failure section 3 exists to
prevent. In that mode the only faithful slow motion is holding each frame for an integer number of
output frames, which is a different feature (a hold count, not a curve) and is visibly steppy at low
counts. Do not build a rate curve for Frames/sec mode. If it is wanted later, offer an explicit
per-range frame hold and say in the UI that it is quantised.

**In the solar-time modes the track is coherent, and it must be integrated rather than sampled.**
The natural value is a rate `r` in solar seconds per output second, the same quantity the speed
spinner already sets. The lane's horizontal axis is data time, so the curve is `r(T)`. Playback is
then the solution of

```
T(n+1) = T(n) + r(T(n)) / f_out
```

which is precisely what varying `deltaT` per tick already does: the existing absolute path is an
explicit Euler integration of a constant rate, and the track makes the rate a function of `T`.
Sampling the curve at uniform output frames instead, without integrating, gives a lane that draws a
rate curve while the movie plays at the old rate. That is the classic time-remap mistake and it is
worth writing down because the code looks correct either way. The integral is also the number the
lane should print beside the curve: the elapsed output time, so "how long will this be" is
answerable before the recording rather than after it.

Both integrations, screen and export, stay the same integration because both go through
`absoluteTimeAdvance`. The recording must never compute its own schedule; that is where the two
would drift apart.

Four limits to state plainly before anyone builds it:

- **There is no frame interpolation anywhere in this application.**
  `Layers.setImageLayersNearestFrame` (Layers.java:397-402) snaps every layer to its nearest
  existing frame. Slowing below one data frame per output frame repeats frames; it does not invent
  intermediate ones. Slow motion through a CME buys smoothness only down to the cadence, and past
  there it is a still image with a moving clock. Speeding up skips frames outright, with no motion
  blur, so a fast pass aliases.
- **`deltaT` is an int and truncates.** `deltaT = 1000 / FPS_ABSOLUTE * sec` (Player.java:412) is
  integer arithmetic; a rate under one solar second per output second gives a `deltaT` of zero and
  the movie stops. It would then trip the stall detector (`STUCK_TICK_LIMIT = 5`, Player.java:139-140,
  193-195) and pop a "Playback stalled" warning at the user. The rate track requires `deltaT` to
  carry a fractional accumulator before anything else in this phase is worth writing.
- **Zero and negative rates are hangs, not effects.** Direction is already owned by
  `Player.AdvanceMode` (Loop, Stop, Swing, SwingDown). Clamp the track strictly positive, at the
  existing `PLAYBACK_SPEED_MIN` (ViewState.java:211-212), and refuse to place a key below it, rather
  than finding out in the middle of a recording.
- **On screen the pacing is only as good as a Swing timer.** `EDTTimer.setDelay` takes int
  milliseconds (EDTTimer.java:19-21). The recording, being grab-paced, is exact. So the frame times
  agree in both and only the wall-clock delivery differs, which is acceptable, but a rate ramp will
  look smoother in the file than in the preview and someone should be told that once.

Recommendation: build the rate track last, restrict it to the solar-time speed units, define its
value as solar seconds per output second, integrate it, and clamp it positive. If Frames/sec mode is
the mode Gilly actually works in, then this track is not the feature he wants and we should talk
about frame holds instead before writing any of it.

## 5. The UI

**The gesture.** Right-click on a slider that has been bound to a parameter key: a popup with
"Animate" (or "Stop animating" when a track exists), "Add key at playhead", and "Remove track".
`JHVSlider` is final, is the single class behind essentially every slider in the application, and
already installs a `MouseAdapter` for its double-click-to-default gesture (JHVSlider.java:14-22), so
the popup goes there once. It asks the registry whether this particular slider carries a key;
sliders that were never registered get no menu, so nothing changes for the several dozen controls
out of scope. Registration is one line at each construction site, twelve of them for version 1.
Choosing Animate creates a track holding one key at the playhead with the current value, adds a row
to the Timelines panel, and reveals the panel if it is hidden.

**The lane.** A `TimelineLayer` whose name is the parameter's label ("AIA 171 Opacity"), so it gets
the enable checkbox, the delete button and the selected-row options panel for free
(TimelinePanel.java:143-159, 173-182). The options panel carries the interpolation default, the
value readout and a "flatten to constant" button. It draws inside its own horizontal strip of the
shared plot rectangle, exactly as `CoverageTimelineLayer` already bottom-justifies its coverage rows
(CoverageTimelineLayer.java:93-108): clip to `graphArea`, compute its own y from a strip index, and
do not use `GraphGeometry.yMapper`, which spans the whole plot height (GraphGeometry.java:62-64).
`showYAxis()` returns false, because every layer that answers true steals `RIGHT_AXIS_WIDTH = 30`
pixels of plot width (GraphGeometry.java:17-18, DrawConstants.java:28) and eight animated parameters
would eat 240 of them. The lane prints its own min and max at its left edge instead, and the live
value at the right.

**Every mouse gesture on the lane** is new, and all of them are gated by a hit test taken at press
time (section 8, trap 3):

| gesture | effect |
|---|---|
| press on a key, drag | move it in time and value; snaps to the nearest frame time (`Player.frameForTime`, Player.java:307-323) unless Shift is held |
| press on the curve, drag vertically | move the whole segment, both bounding keys, in value |
| double-click on the curve | insert a key there |
| double-click on a key | delete it |
| right-click on a key | interpolation mode for the segment leaving it |
| anything outside a lane strip | unchanged: press seeks, Shift-drag pans, Option-drag trims |

**Latch.** While the user is dragging the parameter's own slider, the applier stops writing that key
for as long as `getValueIsAdjusting()` is true, so hand and curve do not fight; on release the value
at the playhead is written as a key. That is the DAW touch behaviour, and it is the behaviour that
makes "animate, then correct it by hand" work.

The reverse direction, mirroring the animated value back into the slider on every frame, should not
be built. Half the sliders would feed back (section 8, trap 2), the others would need a `syncing`
flag each, and it is a lot of Swing churn to display a number the lane is already showing. If it is
missed, mirror only while playback is paused.

**Turning it off**, three ways that mean three different things: un-tick the lane's row and the
track stays but stops applying, leaving the parameter where it was; hit the row's delete column and
the track is gone; right-click the slider and choose "Stop animating", which is the same deletion
from where the user is looking. No global bypass switch in version 1, though it is one boolean in
the applier if it turns out to be wanted.

## 6. Persistence

A new top-level `automation` object inside `org.helioviewer.jhv.state`, written by `State.toJson`
(State.java:114-156) beside `layers`, `imageLayers` and `timelines`, and read in `State.load`
(State.java:372-396):

```json
"automation": { "tracks": [
  { "key": "layer:8f3c.../opacity", "enabled": true,
    "keys": [ {"t": "2026-04-02T14:00:00", "v": 0.20, "i": "SMOOTH"},
              {"t": "2026-04-02T14:30:00", "v": 1.00, "i": "HOLD"} ] } ] }
```

Times as formatted strings, like every other time in the file (`TimeUtils.format`, State.java:120-121).

**Not** in the `timelines` array, and this is the load-bearing decision. `State.load` reads that
array only when the timelines plugin is active: `if (PluginManager.isActive(EVEPlugin.class))
loadTimelines(jo); else Log.info("Skipping timeline state because EVEPlugin is inactive")`
(State.java:381-384). `saveTimelineState` (State.java:178-189), by contrast, writes it
unconditionally from `TimelineLayers.get()`, which is empty when the plugin was never installed
(the list is only populated by the `Timelines` constructor, Timelines.java:29-32). So a track stored
as a timeline layer would survive one session with the panel off and be written away to nothing by
the next autosave. Automation is a property of the picture, not of the panel that edits it.

**With the plugin disabled** the tracks load, apply and record, and there is no lane to edit them
in. The slider's right-click menu then offers "Stop animating" as usual, while "Animate" says it
needs the Timelines panel. That is honest, and it is far better than the alternative: a movie
recorded with the panel open must record identically with it closed.

**Old sessions** carry no layer ids. A layer without one gets a fresh id on load, and a track whose
key names an id no layer carries stays unresolved and is written back out unchanged. No migration is
needed, because no session in existence contains a track.

## 7. The lane-layout decision

Two ways to give lanes vertical space.

**Keep the overlapping full-height model** and let each lane allocate its own strip inside the
shared rectangle, exactly as the coverage track already does. Cost: nothing structural, but every
layer still draws over the same pixels, so an automation lane can be overdrawn by a radio
spectrogram or a band curve; strips have to be assigned by a convention (automation stacking down
from the top, coverage up from the bottom, in registration order); and the plot's preferred height
is 50 pixels (ChartDrawGraphPane.java:57), so more than about three lanes needs the user to drag the
splitter taller. It will look busy.

**Add real per-lane allocation**: `GraphGeometry.layout` hands each layer a rectangle,
`TimelineLayer.draw` takes it, and existing layers receive the whole plot so nothing changes for
them. Cost: a signature change on one interface with six implementations, a `GraphGeometry.yAxisHit`
(GraphGeometry.java:70-76) that has to know which lane owns which band, and `DrawController`'s axis
pan, zoom and reset (DrawController.java:140-193) reworked from "per y-axis index" to "per lane".
Call it two days, all of it in code that currently works.

Recommendation: the first, for version 1. Not only because it is smaller. The second is much easier
to get right once the drawing code exists and its real needs are known than it is to guess at now,
and the first makes no promise the second would have to break. Revisit it the first time four lanes
are open at once and the picture is unreadable.

## 8. Traps

1. **Settings-file writes.** `HdrGain.setSetting`, `setKnee` and `setInRange` each call `commit()`
   (HdrGain.java:118-121, 148-151, 157-160), and `commit` writes three properties (HdrGain.java:167-171).
   `Settings.setProperty` rewrites the whole properties file through a temp file and an atomic move
   whenever the value differs (Settings.java:48-53). The `aim` variants exist for exactly this
   reason and say so (HdrGain.java:123-133). The applier calls `aimSetting`, `aimKnee`, `aimInRange`
   and never `commit`. `Display.setDiskScale` is worse: it writes `display.diskScale` *and* calls
   `DisplayController.render(1)` (Display.java:527-531). Its write-free half already exists,
   `Display.applyDiskScale` (Display.java:538-540), but is package-private, so either the applier
   lives in `org.helioviewer.jhv.display` or that method is widened. `Display.setSkyFieldDegrees`
   (Display.java:607-609) has the same shape and no aim variant, which is why the sky field is not
   in the version 1 list.

2. **The slider feedback loop.** The nested `AbstractSliderFilterPanel` calls the setter and
   `DisplayController.display()` on every `ChangeEvent` with no guard (SliderFilterPanel.java:94-99),
   and so do the enhance and both upsilon sliders (ImageFilterPanel.java:53-58, 78-91). Writing an
   animated value into any of those re-enters the setter and requests a render, once per frame per
   slider. `LevelsPanel` (LevelsPanel.java:33-41, 44-62), `ContrastPanel` (ContrastPanel.java:35-46,
   51-58) and the HDR palette (ColourPaletteContent.java:76-87, 107-115, 128-136) already carry a
   `syncing` flag and a `refresh` method and are safe to mirror. The recommendation in section 5 is
   not to mirror at all.

3. **A press in the plot seeks the movie.** `ChartDrawGraphPane.mousePressed` sets
   `DragMode.MOVIELINE` for any unmodified press (ChartDrawGraphPane.java:269-271) and `mouseDragged`
   calls `DrawController.setMovieFrame` (line 328), which is `Commands.seekTime`
   (DrawController.java:269-273). Hit testing is click-only: `ClickableDrawable` is consulted in
   `mouseClicked` alone (ChartDrawGraphPane.java:232-237), and its one method is
   `clicked(locationOnScreen, timestamp)` (ClickableDrawable.java:5-9). Dragging a key therefore
   needs a new `DragMode.KEYFRAME` chosen at press time, or every attempt to move a key scrubs the
   movie instead.

4. **The existing hit state is stale by up to 100 ms.** `EventTimelineLayer` computes
   `eventUnderMouse` as a side effect of `draw` (EventTimelineLayer.java:92, 124) and reports it
   afterwards (lines 247-268), and the graph image is only rebuilt when `DrawController`'s flag is
   flushed by the UITimer poll, which runs at 10 Hz (DrawController.java:290-311, UITimer.java:18).
   A key hit test built the same way would lag the cursor. Compute it synchronously from the press
   point. Note also that `ChartDrawGraphPane.mouseMoved` calls its own `drawRequest()` directly
   (line 363), which redraws every layer into the image; a key drag repainting per mouse event
   repaints the whole plot, including any radio spectrogram, per mouse event.

5. **The CME tracker drives two of these parameters already.** `CMETracker.solveAndSet` writes
   `Display.setWarpLambda` every frame in WARP mode (CMETracker.java:189-205) and
   `Display.setWarpOuterRadius` in EDGE mode (CMETracker.java:210-224). The toolbar sliders
   disengage tracking on a manual move, guarded by `syncingFromTracker` so a programmatic move does
   not look manual (ToolBar.java:157-159, 639-650, 671-676, 1190-1192). A lambda track and WARP
   tracking are two things writing one value every frame and whichever runs last wins silently. They
   must be mutually exclusive: creating a lambda track stops the tracker, and starting the tracker
   disables (greys, does not delete) the lambda track. Same pairing for the edge track and EDGE mode.

6. **Levels and Contrast are one parameter, held two ways.** `ContrastPanel` writes
   `GLImage.setBrightness` with a width about the current centre (ContrastPanel.java:40-45) and
   `LevelsPanel` writes the same setter with both edges (LevelsPanel.java:39). Two tracks on those
   two rows are two curves fighting over `brightOffset` and `brightScale`. Offer one track pair
   (offset and scale) and leave the contrast row as a hand control, or refuse the second track.

7. **Image filter setters fan out to the selection.** Every one of them goes through
   `Layers.applyToSelected` (Layers.java:438-452), which applies the edit to every selected layer,
   not only the one whose panel it came from. A track must write only the layer it is bound to, so
   it calls the `GLImage` setter directly (GLImage.java:348-392, all pure state) and never the
   panel's consumer.

8. **Do not request a render from inside a render.** `ViewState.setWarpLambda` calls
   `DisplayController.display()` and notifies its mode listeners (ViewState.java:498-507).
   `GridLayer.setGridAlpha`, `setLabelAlpha` and `setGridLineScale` each call `DisplayController.display()`
   too (GridLayer.java:718-741). Called from the applier they ask for another frame from inside a
   frame, every frame. The applier writes the pure setters: `Display.setWarpLambda`
   (Display.java:144-146), `Display.setWarpOuterRadius` (Display.java:189-191), and the field-only
   grid setters, and lets the running frame do the drawing.

9. **An animated grid opacity rebuilds the grid mesh every frame.** `setGridAlpha` sets
   `gridNeedsInit` (GridLayer.java:718-724) because the alpha is baked into the vertex colour bytes
   (GridLayer.java:955), and `render` re-runs `GridMath.initGrid` whenever that flag is set
   (GridLayer.java:284-287). It will work. It is not free. Say so before someone measures it and
   files it as a bug.

10. **The word "keyframe" is already taken in this codebase**, where it means an intra-coded video
    frame: ExportWriter.java:193 and 206, and the user-visible "Every frame a keyframe" checkbox
    (MoviePanel.java:276). Call the record `Key` and the feature "parameter animation", so that a
    bug report about keyframes has exactly one meaning.

11. **Layer identity does not exist yet**, and the two obvious substitutes both fail: names are not
    unique and read "Loading..." while the view is in flight (ImageLayer.java:621-623), and list
    position is decided by an asynchronous restore that also prunes failures (State.java:325-347).
    See section 2.

12. **Upsilon only reaches the shader when RHEF is the active filter**: `rhefActive && !data ?
    upsilonLow : 1` (GLImage.java:169), which is why the palette button is hidden otherwise
    (ImageFilterPanel.java:101). A track on upsilon for a layer whose filter is None draws a curve
    that does nothing. The lane should say so rather than lie quietly.

13. **The timeline strip is composited into recordings.** `ExportMovie` copies `EVEImage` into every
    encoded frame (ExportMovie.java:104-105), and that image is the plot pane's own
    (ChartDrawGraphPane.java:113, 151). So the automation lanes appear in a recorded movie whenever
    the Timelines panel is open. That is the existing behaviour for every timeline and needs no fix,
    but whoever records a finished animation needs to know it.

14. **`GLImage` is null on the placeholder layer** (ImageLayer.java:106-110). Resolving through the
    registry, which only ever holds live registrations, avoids it; reaching into `Layers` from the
    applier does not.

## 9. Build plan

| phase | what | estimate |
|---|---|---|
| 1 | Registry, key grammar, applier at the top of `GLRenderer.display`, aim-variant setters wired for HDR and disk scale, one hard-coded track to prove it | 1 day |
| 2 | `AbstractLayer` ids, written and read by `State`, generated for old sessions | half a day |
| 3 | The `automation` object in the session, saved and loaded outside the plugin gate | half a day |
| 4 | The lane, read-only: one `TimelineLayer` per track drawing its curve in its own strip, with the enable checkbox and delete column | half a day |
| 5 | Gestures: `DragMode.KEYFRAME`, the press-time hit test, drag, insert, delete, right-click interpolation, and the double-click conflict with `resetAxis` | 1.5 days |
| 6 | Slider right-click menu, registration at the twelve sites, the latch and the write-on-release | 1 day |
| 7 | The frame-rate track, if section 4 has not killed it: fractional `deltaT`, rate clamp, mode restriction, the integration and the elapsed-output-time readout | 2 days |

About a week for phases 1 to 6. Phases 1 to 4 are worth having on their own: a session that animates
from a saved file, with no editing UI at all, is already a demo, and it is the half that carries all
the risk of being wrong in a way that shows up in a recording.

Checks worth writing, one per phase: the evaluator against a three-key track in all three
interpolation modes, including both clamps; the key grammar's round trip through save and load,
including a track whose layer id is absent; and, for phase 7, the integrator against an analytic
case (a rate that halves linearly over an interval has a known elapsed output time).

## 10. What I did not verify

- I did not build, run, or exercise anything. No code in this repository was changed, no check was
  run, and the application was not started. Everything above is read from the source.
- Nothing here is measured. The per-frame cost of the grid mesh rebuild, of evaluating a dozen
  tracks, and of a full plot repaint per mouse event during a key drag are all reasoned from the
  code, not timed.
- I did not read `JHVRangeSlider`, so the two-handle Levels gesture inside a lane is described only
  from `LevelsPanel`'s use of it.
- I did not read `GLRenderer`'s viewport loop in detail, so whether a per-layer track should apply
  per viewport when multiview is on is open.
- I did not read the layered EXR path (`ExrCapture`, `ExrWriter`) to check whether its per-layer
  passes re-render with the same parameter state, so whether an animated parameter lands correctly
  in a layered EXR is unverified.
- I did not check `ProjectionTransition` or `SurfaceTransition` behaviour while a track is driving a
  parameter through a transition.
- I did not look at the SAMP command surface or `Commands` to see whether tracks should also be
  scriptable from outside.
- I did not confirm the Swing timer's actual jitter; the claim about on-screen rate ramps follows
  from `EDTTimer.setDelay` taking an int, not from a measurement.
- I assumed "levels" and "contrast" in Gilly's list mean both rows of the image filter panel, which
  is why trap 6 exists rather than a decision.
- The spiral speed is the one version 1 parameter with no line citation anywhere above. Its setter
  is `ViewpointLayerOptionsExpert.setSpiralSpeed` and its slider is in
  `ViewpointLayerOptionsExpertPanel`, but that whole area is being reworked in the working tree as
  this was written (`ViewpointLayerOptions`, `ObserverLayer` renamed to `Turntable`), so pinning a
  line there would have been pinning something already moving. Re-read it before phase 1 registers
  the key.
- Every line number above was checked against the working tree on 2026-09-08. `State.java` and
  `Layers.java` both moved under me while this was being written, so treat any citation that does
  not land as a shift, not as a claim about different code.

## 11. What contact with the code changed

Written 2026-09-08 after building phases 1 to 4. Everything else in this document stood.

**The registry does not take registrations.** Section 2 proposed a registration protocol: a layer
registers its parameters when its view is ready and unregisters when it is removed. What was built
resolves the key per frame instead: `Automation.resolve` switches on the fixed global keys and
otherwise parses `kind:<layerId>/<name>` and looks the id up in `Layers`. This has the property the
registration protocol was there to buy (a key naming a layer that has not loaded yet does nothing
that frame and costs a lookup) with no lifecycle to leak, no registration sites, and no ordering.
The cheap-setter contract survives unchanged: it is the switch's right-hand side. Phase 6 needs a
slider to ask "does this control carry a key", which is the reverse direction and will want a small
map from slider to key; that map is the registration, and it can be added when phase 6 needs it.

**The lane prints no live value.** Section 5's lane was to print the value at the playhead at its
right edge. Built and then removed, because it is wrong on the screen: a timeline layer draws into
the plot's cached image, which is rebuilt only when something sets `DrawController`'s redraw flag,
and a time change does not: the movie line is composited over the cached image afterwards. Measured
in the running application on 2026-09-08, the lane read 0.99 while the track's actual value was
0.33, and did not change across two seeks; it corrected itself only when a pointer event in the
plot forced a redraw. This is trap 4 in a new place. Forcing a repaint per frame would redraw every
timeline layer, a radio spectrogram included, on every frame, so the number was dropped: the movie
line crossing the curve is the readout, and the lane's own min and max bound it. The number belongs
in the selected-row options panel, which Swing repaints on its own, and that panel is phase 5.

**The strict ISO parse, not `TimeUtils.optParse`.** Key times are machine-written by
`TimeUtils.format`, so `TimeUtils.parse` round-trips them to the millisecond. `optParse` routes
through SPICE and a natural-language fallback: it needs a native library that `AppInit` extracts at
startup and no headless check can load, and it would guess at a malformed field rather than skip it.

**Automation lanes are excluded from `saveTimelineState`.** Section 6 decided that tracks are not
stored in the `timelines` array. Enforcing that needs one more line than the section says: the lane
is a `TimelineLayer`, so `saveTimelineState` would write it there anyway, and the session would
carry two copies of each track with only one restore path. `State.saveTimelineState` now skips
`AutomationTimelineLayer`.

**`Display.applyDiskScale` is public**, rather than the applier living in
`org.helioviewer.jhv.display`. Section 8 offered both; this is the shorter diff.

### Verified on 2026-09-08

Built with `ant`; `extra/test/AutomationTrackCheck.java` passes (evaluator in all three
interpolation modes, both clamps, out-of-order insertion, the save/load round trip including a
track whose layer id no layer carries, and the applier writing through the registry). Then run in
the application against a session carrying three tracks: an opacity track on a PUNCH mosaic bound
by layer id, a `display.warpLambda` track, and a track naming a layer id no layer carries. All
three lanes drew, stacked from the top, with the coverage rows undisturbed below them. The opacity
lane's label resolved to "WFI+NFI Mosaic 530 opacity", which is the layer id surviving the save and
the reload. The unresolved track loaded, drew, showed its raw key as its label, applied nothing and
threw nothing. At 2025-09-24T13:31 the lanes read 0.36 and 0.51 against 0.347 and 0.528 computed
from the track definitions.

### Still not verified

- Recording. Nothing was exported, so the claim that the file is the screen is still an argument
  from where the applier sits in `GLRenderer.display`, not an observation.
- Anything in section 10 that was open then is open now, the layered EXR path and the multiview
  viewport loop included.
- The grid mesh rebuild's per-frame cost is still unmeasured.
- Resolving a `layer:<id>/...` key against the live layer list is not covered by the headless
  check: `Layers`' class initialisation reaches SPICE. It is covered by the application run above.
