package org.helioviewer.jhv.app;

import java.lang.management.ManagementFactory;

/**
 * One share of the machine's memory for everything HelioFITS Studio caches, split between the Java
 * heap (JPIP bins) and the decoded frames, which live outside it.
 *
 * <p>Before this the two had separate budgets that did not know about each other: decoded frames
 * took 40% of physical memory on their own and the heap defaulted to 25%, so together they
 * outgrew the machine and went to swap. The share is a Setting, 50% by default, because a larger
 * cache keeps more of a movie ready to loop and a smaller one leaves room for other programs.
 */
public final class MemoryBudget {

    public static final String SHARE_KEY = "memory.sharePercent";
    public static final int DEFAULT_SHARE = 50;
    private static final long FLOOR = 256L << 20; // below this a single frame's bins would not fit

    /** A quarter of the share goes to the heap, the rest to decoded frames. */
    private static final int HEAP_PART = 4;

    public static int sharePercent() {
        try {
            return Math.clamp(Integer.parseInt(Settings.getProperty(SHARE_KEY).trim()), 10, 90);
        } catch (RuntimeException e) { // unset or not a number: the default, never a reason to fail
            return DEFAULT_SHARE;
        }
    }

    public static long physical() {
        return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
    }

    /** Heap plus decoded frames, in bytes. */
    public static long total() {
        return total(physical(), sharePercent());
    }

    /** What JPIP data bins may hold: half the heap's part, since the rest of the heap is the program itself. */
    public static long binBytes() {
        return binBytes(total());
    }

    /** What decoded frames may hold, in native memory. */
    public static long decodedBytes() {
        return decodedBytes(total());
    }

    static long total(long physical, int percent) {
        return physical / 100 * percent;
    }

    static long binBytes(long total) {
        return Math.max(FLOOR, total / HEAP_PART / 2);
    }

    static long decodedBytes(long total) {
        return Math.max(4L << 30 > total ? total : 4L << 30, total - total / HEAP_PART);
    }

    private MemoryBudget() {}

}
