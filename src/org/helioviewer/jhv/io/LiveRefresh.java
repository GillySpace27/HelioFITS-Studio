package org.helioviewer.jhv.io;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.thread.EDTTimer;
import org.helioviewer.jhv.time.TimeUtils;

/**
 * Live refresh for a PUNCH layer (HS-12 phase 2): every {@link #INTERVAL_MILLIS} ask the archive whether
 * it holds anything new for the layer's query and load it as a new layer, the way the Refresh button
 * does. Dormant twice over: the setting {@link #KEY} is off unless Gilly turns it on, and even then a
 * tick does nothing unless the archive's newest day is within {@link #GATE_MAX_AGE_MILLIS} (the archive
 * age gate; a quiet archive is never polled). Old layers are never removed: new frames arrive as a
 * new layer, as with Refresh. One request in flight per layer; failures back off exponentially.
 */
public final class LiveRefresh {

    /** Settings key; absent means off. */
    public static final String KEY = "punch.live";
    /** The archive age gate: newest day older than this and Live does nothing. UNCHECKED against the real archive. */
    public static final long GATE_MAX_AGE_MILLIS = PunchLatest.STALE_MILLIS;
    public static final long INTERVAL_MILLIS = 15 * 60_000L;
    public static final long MAX_BACKOFF_MILLIS = 4 * 3_600_000L;

    /** Delay before the next tick after {@code failures} consecutive failures: 15 min doubling, capped. Pure. */
    static long nextDelay(int failures) {
        long d = INTERVAL_MILLIS;
        for (int i = 0; i < failures && d < MAX_BACKOFF_MILLIS; i++)
            d *= 2;
        return Math.min(d, MAX_BACKOFF_MILLIS);
    }

    /** Whether the archive's newest day is fresh enough to poll. {@code newestDay <= 0} means unknown: closed. Pure. */
    static boolean gateOpen(long newestDay, long now) {
        return newestDay > 0 && now - newestDay <= GATE_MAX_AGE_MILLIS;
    }

    /** One request in flight; a request that never reports back is given up after two intervals. Pure. */
    static final class InFlight {
        private long since = -1;

        synchronized boolean tryBegin(long now) {
            if (since >= 0 && now - since < 2 * INTERVAL_MILLIS)
                return false;
            since = now;
            return true;
        }

        synchronized void end() {
            since = -1;
        }
    }

    public static boolean enabled() {
        return Boolean.parseBoolean(Settings.getProperty(KEY));
    }

    private static final class Loop {
        final ImageLayer layer;
        final Consumer<String> report;
        final InFlight inFlight = new InFlight();
        final EDTTimer timer;
        int failures;

        Loop(ImageLayer layer, Consumer<String> report) {
            this.layer = layer;
            this.report = report;
            timer = new EDTTimer((int) INTERVAL_MILLIS, this::tick);
            timer.setRepeats(false);
        }

        void schedule() {
            timer.setInitialDelay((int) nextDelay(failures));
            timer.restart();
        }

        void tick() {
            FitsRequest r = layer.getFitsRequest();
            if (r == null || !inFlight.tryBegin(System.currentTimeMillis())) {
                schedule();
                return;
            }
            PunchClient.submitGetCoverage(new PunchClient.ReceiverCoverage() {
                @Override
                public void setPunchResponseCoverage(long newestDay) {
                    if (!gateOpen(newestDay, System.currentTimeMillis())) {
                        report.accept("PUNCH Live: the archive's newest day is " + (newestDay > 0 ? TimeUtils.formatDate(newestDay) : "unknown")
                                + ", older than the gate; not polling.");
                        done(false);
                        return;
                    }
                    PunchClient.submitRefresh(layer, result -> {
                        if (result.newCount() > 0)
                            report.accept("PUNCH Live: loaded " + result.newCount() + " new frame(s) as a new layer.");
                        done(false);
                    });
                }

                @Override
                public void setPunchResponseFailed(String message) {
                    report.accept("PUNCH Live: " + message);
                    done(true);
                }
            }, r.level(), r.product());
        }

        void done(boolean failed) {
            failures = failed ? failures + 1 : 0;
            inFlight.end();
            javax.swing.SwingUtilities.invokeLater(this::schedule);
        }
    }

    private static final Map<ImageLayer, Loop> loops = Collections.synchronizedMap(new WeakHashMap<>());

    public static boolean isLive(ImageLayer layer) {
        return loops.containsKey(layer);
    }

    /** Starts polling for {@code layer}; no-op when the setting is off or the layer has no archive query. */
    public static void start(ImageLayer layer, Consumer<String> report) {
        if (!enabled() || layer.getFitsRequest() == null || loops.containsKey(layer))
            return;
        Loop loop = new Loop(layer, report);
        loops.put(layer, loop);
        loop.schedule();
    }

    public static void stop(ImageLayer layer) {
        Loop loop = loops.remove(layer);
        if (loop != null)
            loop.timer.stop();
    }

    private LiveRefresh() {}
}
