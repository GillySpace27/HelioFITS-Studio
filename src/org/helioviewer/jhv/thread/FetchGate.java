package org.helioviewer.jhv.thread;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

import org.helioviewer.jhv.app.Settings;

/**
 * How many frames are being fetched at once across the whole program.
 *
 * <p>Each layer caps its own fetches at four (ImageLayerLoader.FRAME_FETCHERS), but nothing capped
 * the layers: ten layers were forty streams, and a session full of long requests started them all
 * the moment it opened. Every frame fetch now passes through here, so the total stays at
 * {@link #width()} however many layers are loading, and the rest wait their turn.
 *
 * <p>The width is a Setting ({@value #KEY}, default {@value #DEFAULT}) read once at launch: a
 * tradeoff between speed and a server that divides its bandwidth between whatever is in flight.
 * {@link #pause} holds new fetches back (the ones under way finish), for the watchdog.
 */
public final class FetchGate {

    public static final String KEY = "load.fetchers";
    public static final int DEFAULT = 4;

    private static final int WIDTH = readWidth();
    private static final Semaphore PERMITS = new Semaphore(WIDTH, true);
    private static final java.util.concurrent.atomic.AtomicBoolean PAUSED = new java.util.concurrent.atomic.AtomicBoolean();

    private FetchGate() {}

    public static int width() {
        return WIDTH;
    }

    private static int readWidth() {
        try {
            return Math.max(1, Math.min(32, Integer.parseInt(Settings.getProperty(KEY).trim())));
        } catch (RuntimeException e) { // unset or not a number
            return DEFAULT;
        }
    }

    /** Hold new fetches back, or let them go. Fetches already under way are not touched. */
    public static void pause(boolean hold) {
        PAUSED.set(hold);
    }

    public static boolean isPaused() {
        return PAUSED.get();
    }

    /** Run one fetch when a place is free. Throws InterruptedException if the load is cancelled while waiting. */
    public static <T> T get(Callable<T> fetch) throws Exception {
        while (PAUSED.get())
            Thread.sleep(200);
        PERMITS.acquire();
        try {
            return fetch.call();
        } finally {
            PERMITS.release();
        }
    }

    /** As {@link #get}, for a fetch that returns nothing and handles its own failures. */
    public static void run(Runnable fetch) throws InterruptedException {
        while (PAUSED.get())
            Thread.sleep(200);
        PERMITS.acquire();
        try {
            fetch.run();
        } finally {
            PERMITS.release();
        }
    }
}
