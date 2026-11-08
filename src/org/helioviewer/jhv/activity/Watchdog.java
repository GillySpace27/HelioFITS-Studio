package org.helioviewer.jhv.activity;

import java.awt.EventQueue;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.layers.LoadStop;
import org.helioviewer.jhv.thread.AppThread;
import org.helioviewer.jhv.thread.FetchGate;

/**
 * Notices when the event thread has stopped answering and takes the load off it.
 *
 * <p>A thread of its own posts a tiny task to the event queue every second. If the task has not
 * run for {@link #PAUSE_MS} the window is stuck: new frame fetches are held back and the event
 * thread's stack goes to the log, so the next hang is diagnosable. At {@link #CANCEL_MS} every
 * load and download is cancelled (LoadStop). When the event thread answers again the person is
 * asked whether to resume, because a window that was stuck may be stuck again the moment the
 * fetches restart.
 *
 * <p>Limits, stated so nobody trusts it too far. This lives in the program it watches: it cannot
 * help a JVM that has run out of memory or sits in one long collection, and it cannot draw a
 * prompt while Swing is the thing that is stuck. What it can do, because none of it needs the
 * event thread, is stop the work that is feeding the hang. The same stop is a POST to
 * the local API's /stop for when even this is not enough.
 */
public final class Watchdog {

    static final long PAUSE_MS = 10_000;
    static final long CANCEL_MS = 30_000;
    private static final long TICK_MS = 1_000;
    private static final long SLEEP_GAP_MS = 5_000; // a tick this late means the computer slept, not the window

    public enum Stage { NONE, PAUSE, CANCEL }

    private static final AtomicLong BEAT = new AtomicLong(System.nanoTime());
    private static final AtomicBoolean POSTED = new AtomicBoolean();
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean INTERVENED = new AtomicBoolean();
    private static final AtomicBoolean CANCELLED = new AtomicBoolean();

    private Watchdog() {}

    /** Which step a silence of this many milliseconds calls for. */
    public static Stage stageFor(long silentMs) {
        return silentMs >= CANCEL_MS ? Stage.CANCEL : silentMs >= PAUSE_MS ? Stage.PAUSE : Stage.NONE;
    }

    public static void start() {
        if (!STARTED.compareAndSet(false, true))
            return;
        Thread t = AppThread.create(Watchdog::loop, "HFS-Watchdog");
        t.setDaemon(true);
        t.start();
    }

    private static void loop() {
        long last = System.nanoTime();
        while (true) {
            try {
                Thread.sleep(TICK_MS);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            if ((now - last) / 1_000_000L > SLEEP_GAP_MS) // the computer slept; no verdict from a clock that stopped
                BEAT.set(now);
            last = now;
            if (POSTED.compareAndSet(false, true))
                EventQueue.invokeLater(() -> {
                    BEAT.set(System.nanoTime());
                    POSTED.set(false);
                    if (INTERVENED.get())
                        recovered();
                });
            onSilence((now - BEAT.get()) / 1_000_000L);
        }
    }

    /** The effects of a silence of this length. Separate from the clock so it can be checked. */
    public static void onSilence(long silentMs) {
        Stage stage = stageFor(silentMs);
        if (stage == Stage.NONE)
            return;
        if (INTERVENED.compareAndSet(false, true)) {
            FetchGate.pause(true);
            Log.warn("The window has not answered for " + silentMs / 1000 + " s; new fetches are held. Event thread: " + eventThreadStack());
        }
        if (stage == Stage.CANCEL && CANCELLED.compareAndSet(false, true)) {
            int n = LoadStop.cancelAll();
            Log.warn("The window has not answered for " + silentMs / 1000 + " s; " + n + " load(s) cancelled");
        }
    }

    /** Whether the watchdog has stepped in since the window last answered. */
    public static boolean intervened() {
        return INTERVENED.get();
    }

    /** The window answered again: ask what to do about the fetches it held back. */
    private static void recovered() {
        boolean cancelled = CANCELLED.getAndSet(false);
        INTERVENED.set(false);
        String text = (cancelled ? "Downloads were cancelled and new ones are on hold" : "New downloads are on hold")
                + " (Studio stopped responding, or a stop was sent).\nResume downloading? Layers can be refreshed one at a time instead.";
        int choice = javax.swing.JOptionPane.showOptionDialog(org.helioviewer.jhv.gui.MainFrame.get(), text, "Studio seemed stuck",
                javax.swing.JOptionPane.DEFAULT_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE, null,
                new String[]{"Keep on hold", "Resume"}, "Keep on hold");
        if (choice == 1)
            FetchGate.pause(false);
    }

    /** A stop was sent (the local API): hold fetches, and ask about resuming as soon as the window answers. */
    public static void stopped() {
        FetchGate.pause(true);
        CANCELLED.set(true);
        INTERVENED.set(true);
    }

    private static String eventThreadStack() {
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet())
            if (e.getKey().getName().startsWith("AWT-EventQueue")) {
                StringBuilder sb = new StringBuilder();
                for (StackTraceElement f : e.getValue())
                    sb.append("\n    at ").append(f);
                return sb.toString();
            }
        return "(not found)";
    }
}
