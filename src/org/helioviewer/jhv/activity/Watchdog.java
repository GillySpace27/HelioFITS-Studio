package org.helioviewer.jhv.activity;

import java.awt.EventQueue;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.JDialog;
import javax.swing.JOptionPane;

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
 * load and download is cancelled (LoadStop). Once the event thread has answered for
 * {@link #QUIET_MS}, a stall that cancelled nothing resumes by itself: nobody may be at the
 * computer, and one hitch must not hold a night's downloads. A person is asked only when something
 * was cancelled, a stop was sent, or {@value #REPEAT_STALLS} stalls came within five minutes, since
 * then the fetches may be what is feeding it. There is only ever one dialog, not modal, so it
 * cannot stack; further stalls update its text, and it never takes the focus.
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
    static final long QUIET_MS = 30_000; // this long answering before a stall counts as over; a burst becomes one event
    private static final long REPEAT_WINDOW_MS = 300_000;
    private static final int REPEAT_STALLS = 3;
    private static final String RESUME = "Resume", KEEP = "Keep on hold";

    public enum Stage { NONE, PAUSE, CANCEL }

    private static final AtomicLong BEAT = new AtomicLong(System.nanoTime());
    private static final AtomicBoolean POSTED = new AtomicBoolean();
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final AtomicBoolean INTERVENED = new AtomicBoolean();
    private static final AtomicBoolean CANCELLED = new AtomicBoolean();

    // Below here the event thread's alone.
    private static final ArrayDeque<Long> recent = new ArrayDeque<>(); // when the last stalls ended, for REPEAT_STALLS
    private static int stalls; // since fetches were last free
    private static String lastStallAt = "";
    private static long lastStall;
    private static boolean settling, sawCancel, keptOnHold;
    private static JOptionPane pane;
    private static JDialog prompt;

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
                    answered(System.nanoTime() / 1_000_000L);
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

    /** Whether a stall ends by itself, or a person decides. */
    static boolean autoResumes(boolean cancelled, int recentStalls) {
        return !cancelled && recentStalls < REPEAT_STALLS;
    }

    /** The window answered: at this moment, in milliseconds on any steady clock. */
    static void answered(long nowMs) {
        if (INTERVENED.getAndSet(false)) {
            sawCancel |= CANCELLED.getAndSet(false);
            stalls++;
            lastStall = nowMs;
            lastStallAt = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
            recent.addLast(nowMs);
            settling = true;
            if (prompt != null) {
                pane.setMessage(promptText());
                prompt.pack();
            }
        }
        if (settling && nowMs - lastStall >= QUIET_MS) {
            settling = false;
            settle(nowMs);
        }
    }

    private static void settle(long nowMs) {
        while (!recent.isEmpty() && nowMs - recent.peekFirst() > REPEAT_WINDOW_MS)
            recent.removeFirst();
        if (keptOnHold) // the person said hold; the Task Manager's Resume is the way back
            return;
        if (prompt == null && autoResumes(sawCancel, recent.size())) {
            Log.info("The window is answering again; downloads resume after " + stalls + " stall(s)");
            release();
        } else if (prompt == null)
            showPrompt();
    }

    private static void release() {
        FetchGate.pause(false);
        stalls = 0;
        sawCancel = false;
        keptOnHold = false;
    }

    /** A person chose to resume. On the event thread. */
    public static void resume() {
        release();
        settling = false;
        recent.clear();
        closePrompt();
    }

    private static String promptText() {
        return (sawCancel ? "Downloads were cancelled and new ones are on hold" : "New downloads are on hold")
                + " (Studio stopped responding " + (stalls == 1 ? "once" : stalls + " times") + ", last at " + lastStallAt + ", or a stop was sent)."
                + "\nResume downloading? A cancelled layer starts again from Refresh or by adding it again.";
    }

    private static void showPrompt() {
        if (java.awt.GraphicsEnvironment.isHeadless())
            return;
        pane = new JOptionPane(promptText(), JOptionPane.WARNING_MESSAGE, JOptionPane.DEFAULT_OPTION, null, new String[]{KEEP, RESUME}, KEEP);
        prompt = pane.createDialog(org.helioviewer.jhv.gui.MainFrame.get(), "Studio seemed stuck");
        prompt.setModal(false); // a modal one keeps the event queue running under it, which is how they stacked
        prompt.setAutoRequestFocus(false);
        pane.addPropertyChangeListener(JOptionPane.VALUE_PROPERTY, e -> {
            if (e.getNewValue() == JOptionPane.UNINITIALIZED_VALUE)
                return;
            if (RESUME.equals(e.getNewValue()))
                resume();
            else { // Keep on hold, or the window closed
                keptOnHold = true;
                closePrompt();
            }
        });
        prompt.setVisible(true);
    }

    private static void closePrompt() {
        JDialog d = prompt;
        prompt = null;
        pane = null;
        if (d != null)
            d.dispose();
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
