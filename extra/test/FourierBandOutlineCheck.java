package org.helioviewer.jhv.layers.filters;

import org.helioviewer.jhv.image.fourier.FourierParams;

/**
 * The From/To outline says exactly what the filter will refuse.
 *
 * <p>An inverted or empty band used to surface only as a warning dialog, thrown by
 * FourierParams' constructor and caught on Apply: the settings were filled in, the button
 * pressed, and only then did the pair turn out to be the problem. The fields now carry an error
 * outline while they are being typed.
 *
 * <p>That outline is a second copy of a rule that lives in FourierParams, and a second copy can
 * drift: loosen or tighten the constructor and the outline would go on marking the old boundary,
 * either refusing to run with no field marked or marking a band that works perfectly well. This
 * check holds the two together by asking the constructor itself.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.layers.filters.FourierBandOutlineCheck
 */
public final class FourierBandOutlineCheck {

    private static int failures;

    public static void main(String[] args) {
        double[][] pairs = {
                {200, 800},     // the shipped default
                {0, 1},         // zero is a legal low end
                {800, 200},     // inverted
                {500, 500},     // empty
                {-1, 800},      // negative low end
                {-800, -200},   // both negative
                {0, 0},
        };
        for (double[] pair : pairs) {
            boolean marked = SequencePanel.badBand(pair[0], pair[1]);
            expect(marked == refused(pair[0], pair[1]),
                    "lo=" + pair[0] + " hi=" + pair[1] + ": the outline and the filter disagree (outline says "
                            + (marked ? "bad" : "fine") + ')');
        }

        System.out.println(failures == 0 ? "FourierBandOutlineCheck: PASS" : "FourierBandOutlineCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    /** Whether FourierParams itself would reject this band. */
    private static boolean refused(double lo, double hi) {
        try {
            new FourierParams(FourierParams.Kind.RADIAL, FourierParams.Mode.PASS, lo, hi,
                    FourierParams.Direction.BOTH, 1, 1024, 512);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static void expect(boolean condition, String what) {
        if (!condition) {
            System.out.println("FAIL: " + what);
            failures++;
        }
    }

    private FourierBandOutlineCheck() {}
}
