package org.helioviewer.jhv.view.j2k.opj;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;

/** A decode started on an interrupted thread stops at the first read and reports a cancellation, not a corrupt image. */
public final class OpjCancelCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        try {
            OpenJpeg.version();
        } catch (Throwable t) { // as OpenJpegDecodeCheck: the macOS runners cannot load libopenjp2
            System.out.println("  skip  OpenJPEG is not installed here: " + t.getMessage());
            System.out.println("OpjCancelCheck: SKIPPED");
            return;
        }
        byte[] stream = Files.readAllBytes(Path.of("extra/test/data/opj-12bit.j2k"));

        expect("an ordinary decode still works", OpenJpeg.decode(stream, false, 0, 1).width() == 128);

        Thread.currentThread().interrupt();
        Throwable thrown = null;
        long start = System.nanoTime();
        try {
            OpenJpeg.decode(stream, false, 0, 1);
        } catch (Throwable t) {
            thrown = t;
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        boolean wasInterrupted = Thread.interrupted(); // clear it for the rest
        expect("an interrupted thread's decode throws CancellationException, got " + thrown, thrown instanceof CancellationException);
        expect("promptly, " + ms + " ms", ms < 500);
        expect("and leaves the interrupt flag as it found it", wasInterrupted);
        expect("the next decode on the same thread works", OpenJpeg.decode(stream, false, 0, 1).height() == 96);

        System.out.println(failures == 0 ? "OpjCancelCheck: PASS" : "OpjCancelCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private OpjCancelCheck() {}

}
