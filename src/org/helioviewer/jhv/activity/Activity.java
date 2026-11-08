package org.helioviewer.jhv.activity;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

import javax.annotation.Nullable;

/**
 * What Studio is doing right now, for the footer's lights and the Activity window.
 *
 * <p>Two kinds of signal. A pulse ({@link #pulse}) says something just happened: a command
 * arrived, bytes moved. A level ({@link #begin}/{@link #end}) says something is under way: a
 * download in flight, a worker computing. A light is lit while its level is above zero, and for
 * {@link #HOLD_NANOS} after its last pulse, so one short event is still seen, the way an old disk
 * light flickers.
 *
 * <p>Both are cheap enough for any thread and any rate: a pulse is one clock read and one atomic
 * write, a level one atomic add. Nothing here allocates or touches the event thread. Events for the
 * log ({@link #event}) are coarser (one per command, request, file or Core run) and go into a ring
 * of fixed size.
 */
public final class Activity {

    /** One light each, plus the kinds that appear only in the log. */
    public enum Kind {
        CMD("Command", "a command from outside: the local API or SAMP"),
        CPU("Compute", "worker threads decoding, filtering or rendering"),
        DISK("Disk", "bytes read from or written to storage"),
        NET("Network", "bytes crossing the network"),
        MEM("Memory", "how much room is left"),
        LOG("Log", "a line of Studio's log"),
        CORE("Core", "HelioFITS Core, run as its own process");

        public final String label;
        public final String meaning;

        Kind(String _label, String _meaning) {
            label = _label;
            meaning = _meaning;
        }

        /** The kinds with a light in the footer, in the order shown. */
        public static final List<Kind> LIGHTS = List.of(CMD, CPU, DISK, NET, MEM);
    }

    /** One line of the activity log. {@code seq} counts from 1 and never repeats in a run. */
    public record Event(long seq, long timeMillis, Kind kind, String source, String text) {}

    /** A light stays on this long after its last pulse: one 10 Hz tick, so a single event is seen. */
    public static final long HOLD_NANOS = 120_000_000L;
    public static final int CAPACITY = 5000;
    static final int TEXT_MAX = 500;

    private static final int N = Kind.values().length;
    private static final AtomicLongArray lastPulse = new AtomicLongArray(N);
    private static final AtomicLongArray pulses = new AtomicLongArray(N);
    private static final AtomicInteger[] levels = new AtomicInteger[N];
    private static final LongAdder netBytes = new LongAdder();
    private static final LongAdder diskBytes = new LongAdder();

    private static final Event[] ring = new Event[CAPACITY];
    private static final AtomicLong nextSeq = new AtomicLong(1);

    static {
        for (int i = 0; i < N; i++) {
            levels[i] = new AtomicInteger();
            lastPulse.set(i, Long.MIN_VALUE / 2);
        }
    }

    private Activity() {}

    public static void pulse(Kind kind) {
        lastPulse.set(kind.ordinal(), System.nanoTime());
        pulses.incrementAndGet(kind.ordinal());
    }

    /** Something of this kind is under way until the matching {@link #end}. */
    public static void begin(Kind kind) {
        levels[kind.ordinal()].incrementAndGet();
        pulse(kind);
    }

    public static void end(Kind kind) {
        if (levels[kind.ordinal()].decrementAndGet() < 0)
            levels[kind.ordinal()].set(0); // an unmatched end must not leave the light dark forever after
        pulse(kind);
    }

    public static int level(Kind kind) {
        return levels[kind.ordinal()].get();
    }

    /** How many pulses this kind has had: a light that has not changed count need not repaint. */
    public static long pulses(Kind kind) {
        return pulses.get(kind.ordinal());
    }

    public static boolean lit(Kind kind, long nowNanos) {
        return levels[kind.ordinal()].get() > 0 || nowNanos - lastPulse.get(kind.ordinal()) < HOLD_NANOS;
    }

    public static void netBytes(long n) {
        if (n > 0) {
            netBytes.add(n);
            pulse(Kind.NET);
        }
    }

    public static void diskBytes(long n) {
        if (n > 0) {
            diskBytes.add(n);
            pulse(Kind.DISK);
        }
    }

    public static long netBytesTotal() {
        return netBytes.sum();
    }

    public static long diskBytesTotal() {
        return diskBytes.sum();
    }

    /** A stream whose every read counts as network bytes. */
    public static InputStream countingNet(InputStream in) {
        return new FilterInputStream(in) {
            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b >= 0)
                    netBytes(1);
                return b;
            }

            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                int n = super.read(buf, off, len);
                netBytes(n);
                return n;
            }
        };
    }

    // log -----------------------------------------------------------------------------------------

    /** Adds one line to the activity log and pulses its light. Text longer than 500 characters is cut. */
    public static Event event(Kind kind, String source, String text) {
        long seq = nextSeq.getAndIncrement();
        Event e = new Event(seq, System.currentTimeMillis(), kind, source, cut(text));
        synchronized (ring) {
            ring[(int) ((seq - 1) % CAPACITY)] = e;
        }
        if (kind != Kind.LOG)
            pulse(kind);
        return e;
    }

    public static String cut(String text) {
        return text.length() <= TEXT_MAX ? text : text.substring(0, TEXT_MAX - 1) + "…";
    }

    /** The sequence number the next event will take. */
    public static long nextSeq() {
        return nextSeq.get();
    }

    /**
     * Events after {@code sinceSeq} (0 for all still held), oldest first, of the given kinds (all
     * when null or empty), at most {@code limit}. Events older than the ring holds are gone.
     */
    public static List<Event> since(long sinceSeq, @Nullable Set<Kind> kinds, int limit) {
        Set<Kind> want = kinds == null || kinds.isEmpty() ? EnumSet.allOf(Kind.class) : kinds;
        List<Event> out = new ArrayList<>();
        synchronized (ring) {
            long last = nextSeq.get() - 1;
            long first = Math.max(Math.max(1, sinceSeq + 1), last - CAPACITY + 1);
            for (long s = first; s <= last && out.size() < limit; s++) {
                Event e = ring[(int) ((s - 1) % CAPACITY)];
                if (e != null && e.seq() == s && want.contains(e.kind()))
                    out.add(e);
            }
        }
        return out;
    }

}
