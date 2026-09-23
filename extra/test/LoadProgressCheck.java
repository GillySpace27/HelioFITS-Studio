package org.helioviewer.jhv.layers;

import java.lang.reflect.Method;

import static org.helioviewer.jhv.layers.ImageLayerLoader.CONNECTING;

/**
 * A multi-frame load has to look alive while one frame is still on the wire.
 *
 * <p>The readout counted frames and nothing else. A PUNCH mosaic is tens of megabytes, and on a
 * slow day at the archive (measured 2026-09-22: 328 kB/s from umbra, 39 MB a PAM frame) one of
 * them takes minutes, so "Retrieving: 33/45 frames" sat unchanged for minutes at a time next to a
 * spinner. That is indistinguishable from a hang, and on that same load 37 of the 45 frames went
 * on to time out with nothing said about it.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.LoadProgressCheck
 */
public final class LoadProgressCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    /** @param sinceByteSeconds how long ago the last byte landed; past a few seconds that is a stall */
    private static String text(int downloaded, int cached, int total, long bytes,
                               double elapsedSeconds, double sinceByteSeconds) throws Exception {
        Method m = ImageLayerLoader.class.getDeclaredMethod("progressText",
                int.class, int.class, int.class, long.class, long.class, long.class);
        m.setAccessible(true);
        long now = System.nanoTime();
        return (String) m.invoke(null, downloaded, cached, total, bytes,
                now - (long) (elapsedSeconds * 1e9), now - (long) (sinceByteSeconds * 1e9));
    }

    private static String text(int downloaded, int cached, int total, long bytes, double elapsedSeconds)
            throws Exception {
        return text(downloaded, cached, total, bytes, elapsedSeconds, 0); // bytes still flowing
    }

    public static void main(String[] args) throws Exception {
        String early = text(0, 0, 45, 0, 0.2);
        expect("before anything has happened it says it is connecting: " + early, CONNECTING.equals(early));

        // A restored session reads its frames back off the disk. Nothing crosses the wire, and a
        // counter that only says "12/45" makes that indistinguishable from starting over.
        String restoring = text(0, 21, 45, 0, 3);
        expect("frames off the disk are named as such: " + restoring,
                restoring.equals("Restoring 21/45 from cache"));

        // The whole point: bytes move while the frame count stands still.
        String a = text(3, 0, 45, 120_000_000L, 10);
        String b = text(3, 0, 45, 132_000_000L, 11);
        expect("bytes advance while the frame count stands still: " + a + "  ->  " + b, !a.equals(b));
        expect("megabytes are reported, not bytes", a.contains("120 MB"));
        expect("and a rate once the clock has run: " + a, a.contains("12.0 MB/s"));
        expect("in the verb it is actually in: " + a, a.startsWith("Downloading "));

        // A mixed load says how much of it was free, which is the question a resume raises.
        String mixed = text(9, 21, 45, 167_000_000L, 20);
        expect("a mixed load counts the cache separately: " + mixed, mixed.contains("21 cached"));
        expect("and still totals them: " + mixed, mixed.contains("30/45"));
        expect("the row stays inside the width that clipped the last one (" + mixed.length() + " chars)",
                mixed.length() <= 36);

        // A stall is the archive going quiet, which happens for minutes at a time. The running
        // average only decays; the words have to say it outright or a frozen readout reads as a
        // hung application.
        String stalled = text(3, 0, 45, 120_000_000L, 300, 30);
        expect("a wire that has gone quiet says so: " + stalled, stalled.startsWith("Waiting on host"));
        expect("and still reports what did arrive: " + stalled, stalled.contains("120 MB"));
        expect("without claiming a rate it is not achieving", !stalled.contains("MB/s"));

        if (failures != 0)
            throw new AssertionError(failures + " load progress failure(s)");
        System.out.println("LoadProgressCheck: PASS");
    }

}
