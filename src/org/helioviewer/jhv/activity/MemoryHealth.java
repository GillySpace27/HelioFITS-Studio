package org.helioviewer.jhv.activity;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

/**
 * The MEM light: whether Studio has room to work, and what to do when it has not.
 *
 * <p>A memory number on its own is not actionable: a full decoded-frame cache is the cache doing its
 * job. What says Studio is short of memory is the Java heap still nearly full just after a
 * collection, and the collector taking a large part of the time. Those two set the colour. The
 * machine's own free memory and the decoded frames are shown beside them as context.
 *
 * <p>Thresholds (heap after collection 80% and 92%, collector time 10% and 25% of the last ten
 * seconds) are judgement, not measurement: set so that green is the normal state while a movie loops.
 */
public final class MemoryHealth {

    public enum Level {
        OK, TIGHT, CRITICAL
    }

    /**
     * One reading. {@code heapAfterGc} and {@code gcShare} are fractions; {@code decoded} and
     * {@code decodedMax} are bytes of decoded frames held and allowed; {@code machineFree} and
     * {@code machineTotal} the machine's; {@code footprint} what macOS counts against Studio (0 elsewhere).
     */
    public record Reading(Level level, double heapAfterGc, long heapMax, double gcShare, long decoded, long decodedMax,
                          long machineFree, long machineTotal, long footprint, String reason, String advice) {

        public String summary() {
            return String.format(Locale.ROOT, "heap %.0f%% of %s after GC, collector %.0f%% of time, decoded frames %s of %s, machine %s free of %s%s",
                    heapAfterGc * 100, gib(heapMax), gcShare * 100, gib(decoded), gib(decodedMax), gib(machineFree), gib(machineTotal),
                    footprint > 0 ? ", Studio's footprint " + gib(footprint) : "");
        }
    }

    static final double HEAP_TIGHT = 0.80, HEAP_CRITICAL = 0.92;
    static final double GC_TIGHT = 0.10, GC_CRITICAL = 0.25;
    static final double MACHINE_TIGHT = 0.05;

    private static final AtomicReference<Reading> latest = new AtomicReference<>();
    private static final long WINDOW_NANOS = 10_000_000_000L;
    private static final long[] gcWindow = {System.nanoTime(), totalGcMillis()};
    private static final double[] gcAverage = {0};

    private MemoryHealth() {}

    @Nullable
    public static Reading latest() {
        return latest.get();
    }

    /** Takes a reading now. Called by the sampler twice a second; cheap (a few management beans). */
    public static Reading sample(long decoded, long decodedMax, long footprint) {
        long heapMax = Runtime.getRuntime().maxMemory();
        double heapAfterGc = heapAfterGc(heapMax);
        double gcShare;
        synchronized (gcWindow) { // a moving average over about ten seconds, so one long pause does not turn the light red
            long now = System.nanoTime(), gc = totalGcMillis();
            long dt = now - gcWindow[0];
            if (dt > 0) {
                double instant = Math.min(1, (gc - gcWindow[1]) * 1e6 / dt);
                double alpha = Math.min(1, (double) dt / WINDOW_NANOS);
                gcAverage[0] += alpha * (instant - gcAverage[0]);
                gcWindow[0] = now;
                gcWindow[1] = gc;
            }
            gcShare = gcAverage[0];
        }
        long total = 0, free = 0;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            total = os.getTotalMemorySize();
            free = os.getFreeMemorySize();
        }
        Reading r = assess(heapAfterGc, heapMax, gcShare, decoded, decodedMax, free, total, footprint);
        Reading was = latest.getAndSet(r);
        if (was == null || was.level() != r.level())
            Activity.event(Activity.Kind.MEM, "memory", r.level() + (r.reason().isEmpty() ? "" : ": " + r.reason()) + ". " + r.summary());
        return r;
    }

    /** The rule, apart from the reading, so a check can hold it to its thresholds. */
    static Reading assess(double heapAfterGc, long heapMax, double gcShare, long decoded, long decodedMax, long machineFree, long machineTotal, long footprint) {
        Level level = Level.OK;
        StringBuilder reason = new StringBuilder();
        String advice = "Nothing to do.";
        if (heapAfterGc >= HEAP_CRITICAL || gcShare >= GC_CRITICAL) {
            level = Level.CRITICAL;
        } else if (heapAfterGc >= HEAP_TIGHT || gcShare >= GC_TIGHT) {
            level = Level.TIGHT;
        }
        if (heapAfterGc >= HEAP_TIGHT)
            reason.append(String.format(Locale.ROOT, "the Java heap is %.0f%% full even after collection", heapAfterGc * 100));
        if (gcShare >= GC_TIGHT)
            reason.append(reason.isEmpty() ? "" : ", and ").append(String.format(Locale.ROOT, "collecting takes %.0f%% of the time", gcShare * 100));
        if (level != Level.OK)
            advice = "Close layers you are not using or shorten their time range, and save your session: a full heap ends in an out-of-memory error. "
                    + "The heap's size is fixed when Studio starts.";
        if (machineTotal > 0 && machineFree < machineTotal * MACHINE_TIGHT) {
            if (level == Level.OK)
                level = Level.TIGHT;
            reason.append(reason.isEmpty() ? "" : ", and ").append("the computer has under 5% of its memory free");
            if (advice.startsWith("Nothing"))
                advice = "Lower Settings > Memory share so Studio caches fewer decoded frames, or quit other programs.";
        }
        return new Reading(level, heapAfterGc, heapMax, gcShare, decoded, decodedMax, machineFree, machineTotal, footprint, reason.toString(), advice);
    }

    /** Heap still in use after the last collection of each pool, over the heap's maximum. */
    private static double heapAfterGc(long heapMax) {
        long used = 0;
        boolean any = false;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP)
                continue;
            MemoryUsage after = pool.getCollectionUsage();
            if (after != null) {
                used += after.getUsed();
                any = true;
            }
        }
        if (!any) { // no collection yet, or a collector that does not say: what is in use now
            Runtime rt = Runtime.getRuntime();
            used = rt.totalMemory() - rt.freeMemory();
        }
        return heapMax <= 0 ? 0 : (double) used / heapMax;
    }

    private static long totalGcMillis() {
        long sum = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans())
            sum += Math.max(0, gc.getCollectionTime());
        return sum;
    }

    public static String gib(long bytes) {
        return bytes >= 1L << 30 ? String.format(Locale.ROOT, "%.1f GiB", bytes / (double) (1L << 30)) : (bytes >> 20) + " MiB";
    }

}
