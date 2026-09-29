package org.helioviewer.jhv.thread;

import java.awt.EventQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a task on the EDT at a fixed rate, due at whole periods from when it started rather than a
 * delay after the previous tick was handled.
 *
 * <p>For the movie clock. javax.swing.Timer schedules each tick a delay after its timer thread woke
 * for the last one (TimerQueue: now() + delay), not a delay after the last one was due, so every
 * late wake-up moved all the later ticks back: asked for 34 frames a second, the movie ticked every
 * 33 ms on average, about 30 fps (measured 2026-09-28). Its whole-millisecond delay is not the cause;
 * 29 ms for 29.4 would run slightly fast. Here ticks are due on a fixed grid, in nanoseconds, and a
 * late tick does not move the ones after it. A tick that comes due while the previous one is still
 * waiting for the EDT is dropped, not queued, so a busy EDT slows the movie instead of leaving a
 * backlog of frames to catch up on.
 *
 * <p>Call everything but the constructor on the EDT.
 */
public final class FixedRateTimer {

    private static final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = AppThread.create(r, "Movie-Clock");
        t.setDaemon(true);
        return t;
    });

    private volatile Runnable task;
    private long periodNanos;
    private ScheduledFuture<?> future;
    private int generation; // a tick posted before a stop or restart must not run after it
    private final AtomicBoolean posted = new AtomicBoolean();

    public FixedRateTimer(double hz, Runnable _task) {
        task = _task;
        periodNanos = period(hz);
    }

    private static long period(double hz) {
        return Math.round(1e9 / hz);
    }

    public void setTask(Runnable _task) {
        task = _task;
    }

    /** Takes effect at once when running, as the next tick is then one new period away. */
    public void setRate(double hz) {
        periodNanos = period(hz);
        if (future != null)
            restart();
    }

    public void restart() {
        stop();
        int g = generation;
        long p = periodNanos;
        future = clock.scheduleAtFixedRate(() -> {
            if (posted.compareAndSet(false, true))
                EventQueue.invokeLater(() -> {
                    posted.set(false);
                    if (g == generation)
                        task.run();
                });
        }, p, p, TimeUnit.NANOSECONDS);
    }

    public void stop() {
        generation++;
        if (future != null) {
            future.cancel(false);
            future = null;
        }
    }

    public boolean isRunning() {
        return future != null;
    }
}
