package org.helioviewer.jhv.opengl;

/**
 * When the presenter preview may read a frame back, and whether one is owed.
 *
 * <p>A readback stalls the GPU pipeline, so it runs at a few hertz at most, not at the frame
 * rate. Skipping is safe only if the last frame of a burst is not lost: drag the view and let
 * go, and the frames after the last readback would never reach the preview, which would then
 * show the room something it no longer sees. So a skipped frame leaves the clock owing one, and
 * the owner asks for one more frame when it falls due.
 *
 * <p>Pure: times come in as arguments, so the check can drive it without a clock or a screen.
 */
public final class PreviewClock {

    private final long intervalMillis;
    private long last = Long.MIN_VALUE / 2; // far enough back that the first frame is always taken
    private boolean owed;

    public PreviewClock(long _intervalMillis) {
        intervalMillis = Math.max(1, _intervalMillis);
    }

    /** Whether the frame drawn at {@code now} should be read back; a refusal leaves one owed. */
    public boolean take(long now) {
        if (now - last >= intervalMillis) {
            last = now;
            owed = false;
            return true;
        }
        owed = true;
        return false;
    }

    /** Milliseconds until an owed frame may be taken (0 if now), or -1 when nothing is owed. */
    public long owedIn(long now) {
        if (!owed)
            return -1;
        return Math.max(0, last + intervalMillis - now);
    }

    /**
     * The preview's size for a source frame: the source's shape, its long side at most
     * {@code maxLongSide}, never enlarged, never below one pixel.
     */
    public static int[] fit(int sourceWidth, int sourceHeight, int maxLongSide) {
        int w = Math.max(1, sourceWidth), h = Math.max(1, sourceHeight);
        double scale = Math.min(1, maxLongSide / (double) Math.max(w, h));
        return new int[]{Math.max(1, (int) Math.round(w * scale)), Math.max(1, (int) Math.round(h * scale))};
    }

}
