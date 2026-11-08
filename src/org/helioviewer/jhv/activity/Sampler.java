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
 * The readings no code path reports by itself, taken off the event thread ten times a second: the
 * process's disk traffic from the operating system ({@link IoCounters}), its CPU load (which catches
 * native work outside Studio's own pools), and twice a second the memory health.
 */
public final class Sampler {

    /** Setting: read the operating system's per-process I/O counters (on by default). */
    public static final String OS_COUNTERS_KEY = "activity.osCounters";

    private static final long PERIOD_MS = 100;
    /** CPU counts as busy above half of one core: below that it is the program idling. */
    private static final double BUSY_CORES = 0.5;

    private static final AtomicBoolean started = new AtomicBoolean();
    private static final AtomicLong lastDisk = new AtomicLong(-1);
    private static final AtomicLong ticks = new AtomicLong();
    private static final AtomicLong footprint = new AtomicLong();

    private Sampler() {}

    public static boolean osCountersEnabled() {
        return !"false".equals(Settings.getProperty(OS_COUNTERS_KEY));
    }

    public static void start() {
        if (!started.compareAndSet(false, true))
            return;
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> AppThread.create(r, "HFS-ActivitySampler"));
        timer.scheduleWithFixedDelay(Sampler::tick, PERIOD_MS, PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    static void tick() {
        try {
            disk();
            cpu();
            if (ticks.incrementAndGet() % 5 == 0)
                MemoryHealth.sample(ImageBufferCache.heldBytes(), MemoryBudget.decodedBytes(), footprint.get());
        } catch (RuntimeException e) { // a reading that fails this tick is tried again on the next; the sampler must not die
            Activity.event(Activity.Kind.LOG, "activity", "Activity sampler: " + e);
        }
    }

    private static void disk() {
        if (!osCountersEnabled())
            return;
        IoCounters.Sample s = IoCounters.sample();
        if (s == null)
            return;
        footprint.set(s.footprint());
        long total = s.read() + s.written();
        long was = lastDisk.getAndSet(total);
        if (was >= 0)
            Activity.diskBytes(total - was);
    }

    private static void cpu() {
        if (DecodePool.executor().getActiveCount() > 0) // decoding frames: the commonest compute
            Activity.pulse(Activity.Kind.CPU);
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            double load = os.getProcessCpuLoad(); // of the whole machine, 0 to 1; negative when not known yet
            if (load > 0 && load * os.getAvailableProcessors() >= BUSY_CORES)
                Activity.pulse(Activity.Kind.CPU);
        }
    }

}
