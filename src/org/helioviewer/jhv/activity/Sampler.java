package org.helioviewer.jhv.activity;

import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.helioviewer.jhv.app.MemoryBudget;
import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.image.ImageBufferCache;
import org.helioviewer.jhv.thread.AppThread;
import org.helioviewer.jhv.thread.DecodePool;

/**
 * The readings no code path reports by itself, taken off the event thread ten times a second while
 * the program is working and once a second while it idles: the process's disk traffic from the
 * operating system ({@link IoCounters}), its CPU load (which catches native work outside Studio's
 * own pools), and twice a second the memory health.
 */
public final class Sampler {

    /** Setting: read the operating system's per-process I/O counters (on by default). */
    public static final String OS_COUNTERS_KEY = "activity.osCounters";

    private static final long PERIOD_MS = 100;
    /** Idle period: nothing decoding, CPU below {@link #BUSY_CORES}, disk quiet. Disk totals stay exact, they are differences of a running count. */
    private static final long IDLE_PERIOD_MS = 1000;
    private static final long BUSY_DISK_BYTES = 64 * 1024;
    private static final long MEMORY_PERIOD_MS = 500;
    /** CPU counts as busy above half of one core: below that it is the program idling. */
    private static final double BUSY_CORES = 0.5;

    private static final AtomicBoolean started = new AtomicBoolean();
    private static final AtomicLong lastDisk = new AtomicLong(-1);
    private static final AtomicLong lastMemory = new AtomicLong();
    private static final AtomicLong footprint = new AtomicLong();

    private Sampler() {}

    public static boolean osCountersEnabled() {
        return !"false".equals(Settings.getProperty(OS_COUNTERS_KEY));
    }

    public static void start() {
        if (!started.compareAndSet(false, true))
            return;
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> AppThread.create(r, "HFS-ActivitySampler"));
        schedule(timer, PERIOD_MS);
    }

    private static void schedule(ScheduledExecutorService timer, long delayMs) {
        timer.schedule(() -> schedule(timer, tick() ? PERIOD_MS : IDLE_PERIOD_MS), delayMs, TimeUnit.MILLISECONDS);
    }

    /** One round of readings; true when the program was busy, so the next one comes soon. */
    static boolean tick() {
        boolean busy = false;
        try {
            busy |= disk();
            busy |= cpu();
            long now = System.nanoTime();
            if (now - lastMemory.get() >= MEMORY_PERIOD_MS * 1_000_000L) {
                lastMemory.set(now);
                MemoryHealth.sample(ImageBufferCache.heldBytes(), MemoryBudget.decodedBytes(), footprint.get());
            }
        } catch (RuntimeException e) { // a reading that fails this tick is tried again on the next; the sampler must not die
            Activity.event(Activity.Kind.LOG, "activity", "Activity sampler: " + e);
        }
        return busy;
    }

    private static boolean disk() {
        if (!osCountersEnabled())
            return false;
        IoCounters.Sample s = IoCounters.sample();
        if (s == null)
            return false;
        footprint.set(s.footprint());
        long total = s.read() + s.written();
        long was = lastDisk.getAndSet(total);
        if (was < 0)
            return false;
        Activity.diskBytes(total - was);
        return total - was >= BUSY_DISK_BYTES;
    }

    private static boolean cpu() {
        boolean busy = false;
        if (DecodePool.executor().getActiveCount() > 0) { // decoding frames: the commonest compute
            Activity.pulse(Activity.Kind.CPU);
            busy = true;
        }
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            double load = os.getProcessCpuLoad(); // of the whole machine, 0 to 1; negative when not known yet
            if (load > 0 && load * os.getAvailableProcessors() >= BUSY_CORES) {
                Activity.pulse(Activity.Kind.CPU);
                busy = true;
            }
        }
        return busy;
    }

}
