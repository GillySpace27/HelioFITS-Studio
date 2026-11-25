package org.helioviewer.jhv.gui.status;

/**
 * The footer's FPS is the movie's rate while it plays. It used to be the canvas draw rate, which
 * counts redraws of an unchanged frame, so it read 47 FPS while the movie advanced 2 or 3 frames a
 * second (Gilly, 2026-11-22).
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.status.FramerateTextCheck
 */
public final class FramerateTextCheck {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        String playing = FramerateStatusPanel.text(true, 2.4, 47);
        check(playing.contains("2.4") && !playing.contains("47"), "playing, 2.4 movie frames/s and 47 draws/s: shows 2.4 [" + playing + ']');
        String warming = FramerateStatusPanel.text(true, Double.NaN, 47);
        check(!warming.contains("47"), "playing, no movie rate yet: does not show the draw rate as FPS [" + warming + ']');
        String stopped = FramerateStatusPanel.text(false, Double.NaN, 47);
        check(stopped.contains("47") && !stopped.startsWith("FPS"), "stopped: the draw rate, not called FPS [" + stopped + ']');
        System.out.println(failures == 0 ? "FramerateTextCheck: PASS" : "FramerateTextCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private FramerateTextCheck() {}
}
