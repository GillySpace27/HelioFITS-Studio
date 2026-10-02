package org.helioviewer.jhv.layers.filters;

import org.helioviewer.jhv.image.ImageBuffer;

/**
 * The Levels window read and set in the data's own units (AbsoluteLevels), the "absolute levels"
 * asked for in the 2026-10-02 working meeting.
 *
 * <p>The shader shows texture * gain + offset (imageCommon.frag, fetch), gain being brightScale
 * times the response factor, so black is the texture value where that is 0 and white where it is 1.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.filters.AbsoluteLevelsCheck
 */
public final class AbsoluteLevelsCheck {

    private static int failures;

    private static void expect(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-9 * Math.max(1, Math.abs(b));
    }

    public static void main(String[] args) {
        // Levels at their defaults, 0% and 100%: black and white are the ends of the data.
        double[] w = AbsoluteLevels.window(0, 1, 1);
        expect(near(w[0], 0) && near(w[1], 1), "defaults: black at texture 0, white at 1 (got " + w[0] + ", " + w[1] + ")");

        // Handles at -50% and 150%: the data's middle half fills black to white.
        w = AbsoluteLevels.window(-0.5, 2, 1);
        expect(near(w[0], 0.25) && near(w[1], 0.75), "-50% to 150%: black at 0.25, white at 0.75 (got " + w[0] + ", " + w[1] + ")");

        // The response factor scales the gain, so it moves the window too.
        w = AbsoluteLevels.window(0, 1, 2);
        expect(near(w[0], 0) && near(w[1], 0.5), "response 2: white at texture 0.5 (got " + w[1] + ")");

        // Setting black and white gives back exactly the window that was asked for.
        double[] b = AbsoluteLevels.brightness(0.25, 0.75, 2);
        double[] back = AbsoluteLevels.window(b[0], b[1], 2);
        expect(near(back[0], 0.25) && near(back[1], 0.75), "brightness then window round-trips (got " + back[0] + ", " + back[1] + ")");
        expect(AbsoluteLevels.brightness(0.6, 0.6, 1) == null, "black equal to white is refused");
        expect(AbsoluteLevels.brightness(0.7, 0.2, 1) == null, "black above white is refused");

        // In data units, through a linear PhysicalScale from 10 to 110.
        ImageBuffer.PhysicalScale scale = new ImageBuffer.PhysicalScale(10, 110, x -> x, "linear", x -> x);
        String text = AbsoluteLevels.label(scale, -0.5, 2, 1);
        expect(text.contains("35") && text.contains("85"), "label names black 35 and white 85 (got " + text + ")");
        text = AbsoluteLevels.label(scale, 0.2, 0.6, 1); // the data spans 20% to 80% grey: black is below its minimum
        expect(text.contains("< 10"), "a black point below the data says so (got " + text + ")");

        System.out.println(failures == 0 ? "AbsoluteLevelsCheck: ok" : "AbsoluteLevelsCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }
}
