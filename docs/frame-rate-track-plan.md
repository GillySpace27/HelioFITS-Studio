# Phase 7: the frame-rate track

Written 2026-09-11 against `483150e39`, to be picked up later. Phases 1 to 6 of
`parameter-animation-spec.md` are built and committed; this is the only one left.

This document does not restate the design. Section 4 of the spec is the design, it still stands,
and it should be read first. What is here is everything that was in my head and not in that
section: the decisions already taken, the code as it is today with citations that were checked
today, the order the work has to happen in, and the recipe that verifies it. Section 4 says what to
build and why. This says how to start on a Tuesday without re-deriving it.

## What this costs to defer

Little, and it is worth saying so plainly rather than manufacturing urgency. The design is already
written down, which is the expensive part. Two days of work either way.

Three things decay, and this document is the answer to all three:

- **Line citations rot.** This worktree is shared and moving; `State.java` and `Layers.java` both
  moved under the spec while it was being written on 2026-09-08. Every citation below was checked
  against `483150e39`. Treat one that does not land as a shift, not as a claim about different code.
- **The verification recipe is the perishable part.** Sections "How to prove it works" and "How to
  prove it reaches the file" below are the method that took a morning to work out on 2026-09-11.
  Written down, they are twenty minutes.
- **`Player.java` is the file most likely to move.** The pacing internals below are private and
  small, and nothing outside `Player` and `ViewState` touches them, so the risk is a rename rather
  than a redesign.

## Decisions already taken

Do not relitigate these; they were answered.

1. **Restricted to the solar-time speed units.** Gilly works in both regimes ("both, depending",
   2026-09-08), so the track is offered in Solar minutes/hours/days per second and greyed out in
   Frames/sec **with the reason shown**, not silently missing. Section 4 has the proof that a rate
   curve cannot reach the recorded file in Frames/sec.
2. **The value is a rate**, in solar seconds per output second: the same quantity the speed spinner
   already sets, so the lane's units are the units on screen.
3. **Integrated, not sampled.** `T(n+1) = T(n) + r(T(n)) / f_out`. See section 4.
4. **Clamped strictly positive.** Direction belongs to `Player.AdvanceMode` (Loop, Stop, Swing,
   SwingDown). A zero or negative rate is a hang, not an effect.
5. **Build it last**, which it now is.

## The code as it stands, checked against 483150e39

**The two regimes.** `ViewState.PlaybackSpeedUnit` (ViewState.java:38-56) is `FRAMES_PER_SECOND`
(`secPerSecond` 0, `isRelative()` true) or minutes/hours/days per second (60, 3600, 86400).

**Where a speed becomes pacing.** `ViewState.setPlaybackSpeed` (ViewState.java:598-612) clamps to
`PLAYBACK_SPEED_MIN`..`MAX` = 1..120 (ViewState.java:211-212) and then calls either
`Player.setDesiredRelativeSpeed(speed)` or
`Player.setDesiredAbsoluteSpeed(speed * unit.secPerSecond())`.

**The absolute path, which is the one the track drives** (Player.java:422-426):

```java
public static void setDesiredAbsoluteSpeed(int sec) {
    movieTimer.setTask(Player::absoluteTimeAdvance);
    movieTimer.setDelay(1000 / FPS_ABSOLUTE);
    deltaT = 1000 / FPS_ABSOLUTE * sec;     // int arithmetic; FPS_ABSOLUTE = 30 (Player.java:35)
}
```

`deltaT` is `private static int` (Player.java:133). `absoluteTimeAdvance` (Player.java:197-222)
advances by `time.milli + deltaT` per tick through `nextTime`, and trips the stall detector
(`STUCK_TICK_LIMIT = 5`, Player.java:140, used at 209-216) when the time does not move, which pops
a "Playback stalled" dialog.

So: `1000 / 30 = 33`, then `* sec`. One solar second per output second is already only 33 ms of
solar time per tick, and anything slower than 1 is not expressible at all, because the spinner is
an int with a minimum of 1. **A rate track needs a fractional accumulator before any of the rest of
this phase is worth writing**, and that is a change to `Player`, not to the track.

**The timer** is `EDTTimer` (thread/EDTTimer.java), a thin wrapper over `javax.swing.Timer` whose
`setDelay` takes int milliseconds. On screen the pacing is only as good as that. The recording is
not paced by it at all.

**Why the recording is not paced by the clock.** `ExportMovie` takes the fps once
(ExportMovie.java:140-141) and writes it into the file header:

```java
int fps = playbackData.speedUnit().isRelative() ? playbackData.speed() : Player.FPS_ABSOLUTE;
```

and during a recording `Player.syncTime` returns immediately while a grab is outstanding
(Player.java:347-349), released by `Player.grabDone()` from the export path
(ExportMovie.java:117). One output frame per Player frame, at a constant header rate, however fast
or slow the wall clock was. This is the whole reason a rate curve is coherent in the solar-time
modes and inert in Frames/sec: in the solar-time modes the header rate is fixed at 30 and the rate
curve changes how much *solar time* each of those frames covers, which is real and lands in the
file. In Frames/sec the curve would only change how fast the frames are handed over, which the
grab pacing discards.

## Build order

The first item is a precondition, not a first step among equals: skip it and everything after it is
untestable, because the rates worth animating are the slow ones.

| # | what | why it is here |
|---|---|---|
| 1 | **Fractional `deltaT` in `Player`.** Keep an accumulator (a `double` of milliseconds carried between ticks) and advance by its integer part, retaining the remainder. `setDesiredAbsoluteSpeed` takes a double. | `deltaT` truncating to 0 stops the movie and pops a stall dialog. Nothing below can be exercised at a rate under 1 until this exists. |
| 2 | **A rate setter that does not go through `ViewState.setPlaybackSpeed`.** The applier must move the rate without clamping it to an int, without notifying the playback-config listeners once a frame, and without the spinner fighting it. | Same cheap-setter contract as every other parameter in `Automation.resolve`: no properties write, no fan-out, no repaint request. |
| 3 | **The `player.rate` key in `Automation.resolve`**, getter reading the current solar-seconds-per-output-second, setter calling item 2, clamped to `PLAYBACK_SPEED_MIN`. | One case in the existing switch. |
| 4 | **The mode restriction.** Armed only when `!ViewState.playbackData().speedUnit().isRelative()`; the slider's Animate item present but disabled in Frames/sec, with the reason in its tooltip. | Decision 1. A missing menu item teaches nothing. |
| 5 | **The integration.** `absoluteTimeAdvance` reads the rate at the current `T` rather than a constant. Both screen and export go through this one method, so they cannot drift apart. | Section 4. This is the step that looks correct either way, so write the check (below) first. |
| 6 | **The elapsed-output-time readout** in the lane's options panel, beside the value at the playhead that is already there. | The integral is the answer to "how long will this be", which is worth knowing before the recording rather than after it. |

## How to prove it works

The integration is the part that looks right either way, so it gets an analytic check rather than
an eyeball. Add it to `extra/test/AutomationTrackCheck.java`, which already has the pattern and the
mutation-proving habit.

A rate that halves linearly over an interval has a known elapsed output time. For `r(T)` linear in
`T` from `r0` at `T0` to `r1` at `T1`, the output seconds needed to cross the interval are

```
  integral over T0..T1 of dT / r(T)  =  (T1 - T0) / (r1 - r0) * ln(r1 / r0)
```

so a rate falling from 2 to 1 across an interval of 3600 solar seconds takes
`3600 / (-1) * ln(0.5)` = 2495.3 output seconds. Integrate numerically at the tick rate and assert
the result to a tolerance that a sampled-not-integrated implementation would fail: sampling gives
`3600 / 1.5` = 2400 for the same curve, which is 4% away, so a 1% tolerance separates them. **Write
that assertion before the integration, watch it fail, then make it pass.** That is the whole reason
this check exists; an integrator that agrees with a sampler on the cases you happened to try is the
failure this phase is most likely to ship.

Then mutation-prove it the way the other five were: replace the integration with a sample at the
frame's start time and confirm the named assertion fails.

## How to prove it reaches the file

This is the recipe from 2026-09-11 that verified the applier, and it transfers unchanged. It is
worth following rather than reinventing, because the obvious comparisons do not work: consecutive
frames of a recording differ because the solar data differs, so "the frames change" proves nothing.

1. **Build two sessions that differ in one field.** Take a session with layers already cached, and
   write an `automation` object into it by hand. Session A has `"enabled": true`, session B has
   `"enabled": false`, everything else identical. Keep the recording small and short:
   `{"mode":"LOOP","aspect":"WIDE","longSide":640}` and a `playback` range of about 40 frames.
2. **Launch each with `-state <file>`** rather than clicking through the UI. `CommandLine` honours
   the first `-state` it sees, so an explicit one on the command line wins over the autosave.
3. **Wait for "Fourier filter ready" in the log** before recording, or the first frames record while
   layers are still arriving and the comparison is contaminated. This bit me.
4. **Measure one scalar per frame that the parameter moves**, not a whole-image difference. For the
   warp it was the row at which one instrument's band begins, found by thresholding blue against
   red. For a rate track the natural scalar is the burnt-in timestamp itself: decode it per frame
   and check that the solar time between consecutive frames follows the rate curve. That is a
   better measurement than the warp one, because it is the quantity being animated rather than a
   proxy for it.
5. **Expect the control to be flat.** With the track disabled the scalar was 198 for all 70 frames;
   with it live it moved monotonically 6, 12, 24, 40, 54. Flat versus monotonic is the result.

`ffmpeg` lives in the app's own cache, not on the PATH:
`find ~/HFStudio/Cache -name ffmpeg -maxdepth 2`. It is recent enough that `-vsync` is gone; use
`-fps_mode passthrough`. Recordings land in `~/HFStudio/Exports`.

## What is not settled

- **What happens at a rate the cadence cannot support.** There is no frame interpolation anywhere:
  `Layers.setImageLayersNearestFrame` snaps every layer to its nearest existing frame. Slowing past
  one data frame per output frame repeats frames, so slow motion through a CME buys smoothness only
  down to the cadence and past there is a still image with a moving clock. The lane should probably
  say where that threshold is, perhaps by shading the region of the curve below it. Not designed.
- **Whether the rate track should be excluded from `Swing` and `SwingDown` advance modes**, where
  the direction reverses and an integrated rate has to reverse with it. `absoluteTimeAdvance`
  already handles both directions through `nextTime`; it is not obvious that an integrated rate
  does anything wrong there, and it is not obvious that it does not.
- **Whether the elapsed-output-time readout should account for the trim range.** Almost certainly
  yes, since that is what gets recorded, but the lane currently knows nothing about trim.
- **Frame holds for Frames/sec mode.** Section 4's fallback if the restriction turns out to be the
  wrong answer: an explicit per-range integer frame hold, quantised and labelled as such. Not
  designed, and should not be started before someone actually wants it.

## What not to do

- Do not let the recording compute its own schedule. Both the screen and the export go through
  `absoluteTimeAdvance`; that is what keeps them the same integration, and it is the rule the whole
  applier placement rests on.
- Do not build a rate curve for Frames/sec because it "works on screen". It does, and the file it
  produces is a different movie from the one that was on screen, which is the failure this feature
  is built to avoid.
- Do not clamp the rate by rounding it to the spinner's int. The spinner is the hand control; the
  track carries a double.
