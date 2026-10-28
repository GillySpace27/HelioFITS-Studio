package org.helioviewer.jhv.layers;

import org.helioviewer.jhv.app.Settings;

/**
 * When the next piece of a request longer than the server's frame cap is asked for.
 *
 * <p>A long request is cut into pieces of at most 900 frames (APIRequest.chunks) and each piece is
 * a movie the server builds on demand. Asking early finishes the whole layer sooner; asking late is
 * gentler on the server. It is a Setting (load.chunkMode) because that is a tradeoff, not a fact.
 */
public final class ChunkLoading {

    public static final String KEY = "load.chunkMode";

    public enum Mode {
        /** Ask for the next piece as soon as the previous one has opened. What the loader always did. */
        OPEN(0, "As soon as the previous piece has opened"),
        /** Ask for every piece at once. */
        ALL(0, "All pieces at once"),
        /** Ask when the previous piece is mostly downloaded. */
        MOSTLY(0.8, "When the previous piece is 80% downloaded"),
        /** Ask when the previous piece is completely downloaded. */
        SEQUENTIAL(1.0, "Only when the previous piece has downloaded");

        final double threshold;
        final String label;

        Mode(double _threshold, String _label) {
            threshold = _threshold;
            label = _label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static Mode current() {
        try {
            return Mode.valueOf(Settings.getProperty(KEY).trim());
        } catch (RuntimeException e) { // unset or unknown
            return Mode.OPEN;
        }
    }

    /** Whether a piece with this many of its frames complete is far enough along for the mode. */
    static boolean reached(Mode mode, int complete, int total) {
        return mode.threshold <= 0 || complete >= Math.ceil(mode.threshold * total);
    }

    private ChunkLoading() {}

}
