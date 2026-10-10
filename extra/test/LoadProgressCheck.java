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
    private static String text(int downloaded, int cached, int failed, int total, long bytes,
                               double elapsedSeconds, double sinceByteSeconds) throws Exception {
        Method m = ImageLayerLoader.class.getDeclaredMethod("progressText",
                int.class, int.class, int.class, int.class, long.class, long.class, long.class);
        m.setAccessible(true);
        long now = System.nanoTime();
        return (String) m.invoke(null, downloaded, cached, failed, total, bytes,
                now - (long) (elapsedSeconds * 1e9), now - (long) (sinceByteSeconds * 1e9));
    }

    private static String text(int downloaded, int cached, int total, long bytes,
                               double elapsedSeconds, double sinceByteSeconds) throws Exception {
        return text(downloaded, cached, 0, total, bytes, elapsedSeconds, sinceByteSeconds);
    }

    private static String text(int downloaded, int cached, int total, long bytes, double elapsedSeconds)
            throws Exception {
        return text(downloaded, cached, total, bytes, elapsedSeconds, 0); // bytes still flowing
    }

    /** How much of the line survives the row's clip: the part a reader can actually see. */
    private static final int ROW_CHARS = 36;

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
        int megabytesEnd = mixed.indexOf(" MB") + 3;
        expect("what moves (count and megabytes) sits inside the width the row shows (" + megabytesEnd + " chars)",
                megabytesEnd >= 3 && megabytesEnd <= ROW_CHARS);

        // A restored session with a few frames already cached and large frames on a slow archive
        // (an ASPIICS layer, 0.8.5): four frames of about 17 MB each (AspiicsDialog.FITS_MB) share
        // the wire for minutes and none of them completes. The line used to read
        // "Downloading 4/23 · 4 cached" the whole time, with nothing on it that moved, although
        // the wire was moving.
        String resumeA = text(0, 4, 23, 30_000_000L, 120);
        String resumeB = text(0, 4, 23, 42_000_000L, 180);
        expect("a partly cached load still shows the bytes moving: " + resumeA + "  ->  " + resumeB,
                !resumeA.equals(resumeB));
        expect("and its megabytes are on the visible part of the row: " + resumeA,
                resumeA.contains("30 MB") && resumeA.indexOf("30 MB") + 5 <= ROW_CHARS);
        expect("and it still says how much came from the cache: " + resumeA, resumeA.contains("4 cached"));

        // A frame that dies before its first byte is a failure, not a cache hit. It used to be
        // counted as cached, so a load losing frames looked like a resume going well.
        String failing = text(1, 0, 3, 23, 20_000_000L, 60, 0);
        expect("failed frames are named: " + failing, failing.contains("3 failed"));
        expect("and counted as done, so the count still reaches the total: " + failing, failing.contains("4/23"));
        expect("and not called cached: " + failing, !failing.contains("cached"));
        String allFailed = text(0, 0, 5, 23, 0, 60, 60);
        expect("failures with nothing on the wire are not called a cache restore: " + allFailed,
                !allFailed.startsWith("Restoring") && allFailed.contains("5 failed"));
        String stalledFailing = text(1, 0, 2, 23, 20_000_000L, 300, 30);
        expect("a stall names its failures too: " + stalledFailing,
                stalledFailing.startsWith("Waiting on host") && stalledFailing.contains("2 failed"));

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
