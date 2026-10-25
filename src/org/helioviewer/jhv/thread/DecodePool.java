package org.helioviewer.jhv.thread;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import org.helioviewer.jhv.app.Settings;

/**
 * The one pool every image layer decodes on.
 *
 * <p>Each layer used to own a decode thread, and each decode asked OpenJPEG for up to four more:
 * ten layers meant up to forty native threads on top of the rest of the program, and a hidden or
 * slow layer could not give way to the one being looked at. Now at most {@link #width()} decodes
 * run at once, whatever the layer count, and each takes its share of native threads so that the
 * total stays near the width.
 *
 * <p>The width is a Setting (decode.threads, 0 or unset for automatic): performance cores minus
 * two, so the interface and the network keep a core each. Fewer threads leave the machine quieter,
 * more finish a movie's frames sooner.
 *
 * <p>ponytail: first come first served. A visible layer's requested frame is the only kind of
 * decode J2K layers make (hidden layers are never asked, and only FITS views prefetch, on their
 * own pool), so there is nothing yet to order. Add a priority queue when J2K prefetch exists.
 */
public final class DecodePool {

    public static final String KEY = "decode.threads";
    private static final int MAX_NATIVE = 4; // more threads per decode stop paying off on one frame

    private static final ThreadPoolExecutor executor = AppThread.createIdleExecutor("View-Decoder", width());
    private static final AtomicInteger active = new AtomicInteger();

    public static ThreadPoolExecutor executor() {
        return executor;
    }

    /** The Setting changed: resize the pool now. Running decodes finish; the width applies to the next ones. */
    public static void apply() {
        int width = width();
        if (width > executor.getMaximumPoolSize()) { // grow the maximum first, shrink the core first
            executor.setMaximumPoolSize(width);
            executor.setCorePoolSize(width);
        } else {
            executor.setCorePoolSize(width);
            executor.setMaximumPoolSize(width);
        }
    }

    public static int width() {
        int set = 0;
        try {
            set = Integer.parseInt(Settings.getProperty(KEY).trim());
        } catch (RuntimeException ignored) { // unset or not a number: automatic
        }
        return set > 0 ? Math.min(set, 64) : automatic(performanceCores());
    }

    static int automatic(int cores) {
        return Math.max(1, cores - 2);
    }

    private static int performanceCores() {
        int all = Runtime.getRuntime().availableProcessors();
        if (!System.getProperty("os.name", "").startsWith("Mac"))
            return all;
        try { // Apple Silicon: the performance cores are the ones decodes should land on
            Process p = new ProcessBuilder("sysctl", "-n", "hw.perflevel0.logicalcpu").redirectErrorStream(true).start();
            String line = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            int cores = Integer.parseInt(line);
            return cores > 0 && cores <= all ? cores : all;
        } catch (java.io.IOException | RuntimeException e) { // an Intel Mac has no such key
            return all;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return all;
        }
    }

    /** A decode is starting: how many native threads it should ask OpenJPEG for. Pair with {@link #end()}. */
    public static int begin() {
        return nativeThreads(width(), active.incrementAndGet());
    }

    public static void end() {
        active.decrementAndGet();
    }

    static int nativeThreads(int width, int concurrent) {
        return Math.clamp(width / Math.max(1, concurrent), 1, MAX_NATIVE);
    }

    private DecodePool() {}

}
