package org.helioviewer.jhv.movie;

/**
 * The rate the movie actually advances at, over the last two seconds (RH-16).
 *
 * <p>Not the FPS in the status bar, which counts canvas draws: the canvas redraws for many reasons
 * besides a new frame, and a tick that waits on a slow layer (Player.waitForSlowLayers) draws
 * nothing new. This counts playhead advances while playing, which is what the Play rate asks for,
 * so the two can be read side by side with RHEF on and off.
 *
 * <p>Times are passed in, in nanoseconds, so the arithmetic is checked without a clock.
 */
public final class AchievedRate {

    static final long WINDOW_NANOS = 2_000_000_000L;
    private static final int CAPACITY = 1024; // two seconds at the 512 fps the window could ever need

    private final long[] stamps = new long[CAPACITY];
    private int head; // index of the oldest stamp
    private int count;

    public synchronized void reset() {
        head = 0;
        count = 0;
    }

    /** One frame shown at {@code nanos}. */
    public synchronized void frame(long nanos) {
        if (count == CAPACITY) {
            head = (head + 1) % CAPACITY;
            count--;
        }
        stamps[(head + count) % CAPACITY] = nanos;
        count++;
    }

    /**
     * Frames per second over the stamps within the window ending at {@code nowNanos}; NaN with fewer
     * than two, since one frame in two seconds is not a rate.
     */
    public synchronized double fps(long nowNanos) {
        while (count > 0 && nowNanos - stamps[head] > WINDOW_NANOS) {
            head = (head + 1) % CAPACITY;
            count--;
        }
        if (count < 2)
            return Double.NaN;
        long span = stamps[(head + count - 1) % CAPACITY] - stamps[head];
        return span <= 0 ? Double.NaN : (count - 1) * 1e9 / span;
    }

}
