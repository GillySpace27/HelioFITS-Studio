package org.helioviewer.jhv.movie;

import java.util.ArrayList;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.helioviewer.jhv.app.Message;
import org.helioviewer.jhv.app.state.ViewState;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.thread.EDTTimer;
import org.helioviewer.jhv.time.JHVTime;
import org.helioviewer.jhv.time.TimeListener;
import org.helioviewer.jhv.time.TimeUtils;
import org.helioviewer.jhv.view.View;

public class Player {

    public enum AdvanceMode {
        Loop, Stop, Swing, SwingDown
    }

    public interface Listener {
        void frameChanged(int frame, boolean last);
    }

    public interface StatusListener {
        void movieStatusChanged();
    }

    public static final int FPS_RELATIVE_DEFAULT = 20;
    public static final int FPS_ABSOLUTE = 30;

    private static JHVTime playbackFirstTime = TimeUtils.START;
    private static JHVTime playbackLastTime = TimeUtils.START;
    private static AdvanceMode advanceMode = AdvanceMode.Loop;

    @Nullable
    private static JHVTime nextTime(AdvanceMode mode, JHVTime time,
                                    Supplier<JHVTime> firstTime, Supplier<JHVTime> lastTime,
                                    Function<JHVTime, JHVTime> lowerTime, Function<JHVTime, JHVTime> higherTime) {
        JHVTime next = mode == AdvanceMode.SwingDown ? lowerTime.apply(time) : higherTime.apply(time);
        if (next.milli == time.milli) { // already at the edges
            switch (mode) {
                case Loop -> {
                    if (next.milli == lastTime.get().milli) {
                        return firstTime.get();
                    }
                }
                case Stop -> {
                    if (next.milli == lastTime.get().milli) {
                        return null;
                    }
                }
                case Swing -> {
                    if (next.milli == lastTime.get().milli) {
                        advanceMode = AdvanceMode.SwingDown;
                        return lowerTime.apply(next);
                    }
                }
                case SwingDown -> {
                    if (next.milli == firstTime.get().milli) {
                        advanceMode = AdvanceMode.Swing;
                        return higherTime.apply(next);
                    }
                }
            }
        }
        return next;
    }

    public static void setMaster(ImageLayer layer) {
        if (layer == null) {
            ExportMovie.shallStop();
            playbackFirstTime = TimeUtils.START;
            playbackLastTime = TimeUtils.START;
            ViewState.setPlaybackRange(0, 0);
        } else {
            View view = layer.getView();
            playbackFirstTime = view.getFirstTime();
            playbackLastTime = view.getLastTime();
            ViewState.setPlaybackRange(0, view.getMaximumFrameNumber());
            syncTime(playbackFirstTime);
        }
        notifyStatusChanged();
        timeRangeChanged();
    }

    public static void setPlaybackRange(int firstFrame, int lastFrame) {
        ImageLayer layer = Layers.getActiveImageLayer();
        if (layer == null) {
            playbackFirstTime = TimeUtils.START;
            playbackLastTime = TimeUtils.START;
        } else {
            View view = layer.getView();
            int maximum = view.getMaximumFrameNumber();
            playbackFirstTime = view.getFrameTime(Math.clamp(firstFrame, 0, maximum));
            playbackLastTime = view.getFrameTime(Math.clamp(lastFrame, 0, maximum));
        }
    }

    public static long getStartTime() {
        return movieStart;
    }

    public static long getEndTime() {
        return movieEnd;
    }

    private static long getMovieStart() {
        ImageLayer layer = Layers.getActiveImageLayer();
        if (layer == null || layer.isLoadingForTimespan())
            return lastTimestamp.milli;
        return layer.getStartTime();
    }

    private static long getMovieEnd() {
        ImageLayer layer = Layers.getActiveImageLayer();
        if (layer == null || layer.isLoadingForTimespan())
            return lastTimestamp.milli;
        return layer.getEndTime();
    }

    public static void timeRangeChanged() {
        movieStart = getMovieStart();
        movieEnd = getMovieEnd();
        timeRangeListeners.forEach(listener -> listener.timeRangeChanged(movieStart, movieEnd));
    }

    private static int deltaT;

    // How many consecutive movieTimer ticks have failed to move lastTimestamp forward — a frame
    // whose data never arrives (a stalled/failed network fetch, e.g. a slow archive) can otherwise
    // leave playback silently "playing" in place forever. A few ticks' grace avoids false positives
    // from a single legitimate repeat; past that, we've genuinely stalled.
    private static int stuckTicks;
    private static final int STUCK_TICK_LIMIT = 5;

    private static void relativeTimeAdvance() {
        ImageLayer layer = Layers.getActiveImageLayer();
        if (layer != null) {
            View view = layer.getView();
            JHVTime next = nextTime(advanceMode, lastTimestamp,
                    () -> playbackFirstTime, () -> playbackLastTime,
                    time -> JHVTime.clamp(view.getLowerTime(time), playbackFirstTime, playbackLastTime),
                    time -> JHVTime.clamp(view.getHigherTime(time), playbackFirstTime, playbackLastTime));

            if (next == null) {
                pause();
                return;
            }
            if (isStuck(next)) {
                // The view's own lower/higher traversal can't move past this point (e.g. a gap
                // left by a frame that failed to download) even though we haven't reached the
                // configured end. Try a direct index scan past the stuck point first — it uses
                // the frame list itself rather than the traversal that got stuck.
                JHVTime skip = scanPastGap(view, next);
                if (skip != null) {
                    stuckTicks = 0;
                    syncTime(skip);
                    return;
                }
                if (++stuckTicks >= STUCK_TICK_LIMIT) {
                    stuckTicks = 0;
                    pause();
                    Message.warn("Playback stalled", "Could not advance past " + TimeUtils.format(next.milli)
                            + " — the next frame's data may have failed to download. Paused; scrub to resume.");
                    return;
                }
            } else {
                stuckTicks = 0;
            }
            syncTime(next);
        }
    }

    // Look past a stuck point directly in the frame index (not via getLowerTime/getHigherTime,
    // which is what got stuck) for the next frame within the playback range.
    @Nullable
    private static JHVTime scanPastGap(View view, JHVTime stuckAt) {
        int max = view.getMaximumFrameNumber();
        for (int i = view.getCurrentFrameNumber() + 1; i <= max; i++) {
            JHVTime t = view.getFrameTime(i);
            if (t.milli > stuckAt.milli && t.milli <= playbackLastTime.milli)
                return t;
        }
        return null;
    }

    private static boolean isStuck(JHVTime next) {
        return next.milli == lastTimestamp.milli && next.milli != playbackLastTime.milli;
    }

    private static void absoluteTimeAdvance() {
        ImageLayer layer = Layers.getActiveImageLayer();
        if (layer != null) {
            JHVTime next = nextTime(advanceMode, lastTimestamp,
                    () -> playbackFirstTime, () -> playbackLastTime,
                    time -> new JHVTime(Math.max(playbackFirstTime.milli, time.milli - deltaT)),
                    time -> new JHVTime(Math.min(playbackLastTime.milli, time.milli + deltaT)));

            if (next == null) {
                pause();
                return;
            }
            if (isStuck(next)) {
                if (++stuckTicks >= STUCK_TICK_LIMIT) {
                    stuckTicks = 0;
                    pause();
                    Message.warn("Playback stalled", "Could not advance past " + TimeUtils.format(next.milli) + ". Paused; scrub to resume.");
                    return;
                }
            } else {
                stuckTicks = 0;
            }
            syncTime(next);
        }
    }

    private static final EDTTimer movieTimer = new EDTTimer(1000 / FPS_RELATIVE_DEFAULT, Player::relativeTimeAdvance);

    public static boolean isPlaying() {
        return movieTimer.isRunning();
    }

    public static void play() {
        ImageLayer layer = Layers.getActiveImageLayer();
        if (layer != null && layer.getView().isMultiFrame()) {
            movieTimer.restart();
            notifyStatusChanged();
        }
    }

    public static void pause() {
        movieTimer.stop();
        notifyStatusChanged();
        DisplayController.render(1); /* ! force update for on the fly resolution change */
    }

    public static void toggle() {
        if (isPlaying())
            pause();
        else
            play();
    }

    public static void setTime(JHVTime dateTime) {
        ImageLayer layer = Layers.getActiveImageLayer();
        if (layer != null) {
            syncTime(JHVTime.clamp(layer.getView().getNearestTime(dateTime), playbackFirstTime, playbackLastTime));
        }
    }

    public static void setFrame(int frame) {
        ImageLayer layer = Layers.getActiveImageLayer();
        if (layer != null) {
            syncTime(JHVTime.clamp(layer.getView().getFrameTime(frame), playbackFirstTime, playbackLastTime));
        }
    }

    public static void nextFrame() {
        ImageLayer layer = Layers.getActiveImageLayer();
        if (layer != null) {
            syncTime(JHVTime.clamp(layer.getView().getHigherTime(lastTimestamp), playbackFirstTime, playbackLastTime));
        }
    }

    public static void previousFrame() {
        ImageLayer layer = Layers.getActiveImageLayer();
        if (layer != null) {
            syncTime(JHVTime.clamp(layer.getView().getLowerTime(lastTimestamp), playbackFirstTime, playbackLastTime));
        }
    }

    private static JHVTime lastTimestamp = TimeUtils.START;
    private static long movieStart = TimeUtils.START.milli;
    private static long movieEnd = TimeUtils.START.milli;

    public static JHVTime getTime() {
        return lastTimestamp;
    }

    public static boolean isAvailable() {
        ImageLayer layer = Layers.getActiveImageLayer();
        return layer != null && layer.getView().isMultiFrame();
    }

    // Trim (playback range) endpoint times — shared with the bottom timeline so trim markers line up.
    public static long getPlaybackFirstTime() {
        return playbackFirstTime.milli;
    }

    public static long getPlaybackLastTime() {
        return playbackLastTime.milli;
    }

    public static int getCurrentFrameNumber() {
        ImageLayer layer = Layers.getActiveImageLayer();
        return layer == null ? 0 : layer.getView().getCurrentFrameNumber();
    }

    // Frame index whose timestamp is nearest the given time — lets the bottom timeline set a trim
    // point (a frame) from a cursor time.
    public static int frameForTime(long millis) {
        ImageLayer layer = Layers.getActiveImageLayer();
        if (layer == null)
            return 0;
        View view = layer.getView();
        int max = view.getMaximumFrameNumber();
        int best = 0;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i <= max; i++) {
            long d = Math.abs(view.getFrameTime(i).milli - millis);
            if (d < bestDiff) {
                bestDiff = d;
                best = i;
            }
        }
        return best;
    }

    public static int getMaximumFrameNumber() {
        ImageLayer layer = Layers.getActiveImageLayer();
        return layer == null ? 0 : layer.getView().getMaximumFrameNumber();
    }

    private static void notifyStatusChanged() {
        statusListeners.forEach(StatusListener::movieStatusChanged);
    }

    private static void syncTime(JHVTime dateTime) {
        if (ExportMovie.isRecording() && notDone)
            return;

        lastTimestamp = dateTime;
        DisplayController.timeChanged(dateTime);

        Layers.setImageLayersNearestFrame(dateTime);
        DisplayController.render(1);

        timeListeners.forEach(listener -> listener.timeChanged(lastTimestamp.milli));

        View view = Layers.getActiveImageLayer().getView(); // should be not null
        int activeFrame = view.getCurrentFrameNumber();
        boolean last = view.getFrameTime(activeFrame).equals(playbackLastTime);

        frameListeners.forEach(listener -> listener.frameChanged(activeFrame, last));

        if (ExportMovie.isRecording())
            notDone = true;
    }

    private static final ArrayList<Listener> frameListeners = new ArrayList<>();
    private static final ArrayList<StatusListener> statusListeners = new ArrayList<>();
    private static final ArrayList<TimeListener.Change> timeListeners = new ArrayList<>();
    private static final ArrayList<TimeListener.Range> timeRangeListeners = new ArrayList<>();

    public static void addFrameListener(Listener listener) {
        if (!frameListeners.contains(listener))
            frameListeners.add(listener);
    }

    public static void removeFrameListener(Listener listener) {
        frameListeners.remove(listener);
    }

    public static void addStatusListener(StatusListener listener) {
        if (!statusListeners.contains(listener)) {
            statusListeners.add(listener);
            listener.movieStatusChanged();
        }
    }

    public static void removeStatusListener(StatusListener listener) {
        statusListeners.remove(listener);
    }

    public static void addTimeListener(TimeListener.Change listener) {
        if (!timeListeners.contains(listener)) {
            timeListeners.add(listener);
            listener.timeChanged(lastTimestamp.milli);
        }
    }

    public static void removeTimeListener(TimeListener.Change listener) {
        timeListeners.remove(listener);
    }

    public static void addTimeRangeListener(TimeListener.Range listener) {
        if (!timeRangeListeners.contains(listener)) {
            timeRangeListeners.add(listener);
            listener.timeRangeChanged(movieStart, movieEnd);
        }
    }

    public static void removeTimeRangeListener(TimeListener.Range listener) {
        timeRangeListeners.remove(listener);
    }

    public static void setDesiredRelativeSpeed(int fps) {
        movieTimer.setTask(Player::relativeTimeAdvance);
        movieTimer.setDelay(1000 / fps);
        deltaT = 0;
    }

    public static void setDesiredAbsoluteSpeed(int sec) {
        movieTimer.setTask(Player::absoluteTimeAdvance);
        movieTimer.setDelay(1000 / FPS_ABSOLUTE);
        deltaT = 1000 / FPS_ABSOLUTE * sec;
    }

    public static void setAdvanceMode(AdvanceMode mode) {
        advanceMode = mode;
    }

    private static boolean notDone;

    public static void grabDone() {
        notDone = false;
    }

}
