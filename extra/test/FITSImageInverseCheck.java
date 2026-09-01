package org.helioviewer.jhv.view.uri;

import java.util.function.DoubleUnaryOperator;

import org.helioviewer.jhv.image.ImageBuffer;

// Standalone self-check (no test framework in this repo -- see extra/test/LUTLabelsCheck.java for
// the pattern). FITSImage.inverseMapping is hand-derived algebra (asinh/log1p inverses); this
// confirms it actually undoes normalizedMapping's forward stretch for all three scaling modes,
// across the full [0,1] domain -- the property the colorbar hover's "physical value" reading
// depends on. A sign or formula error here would show a plausible-looking but wrong data value.
public final class FITSImageInverseCheck {

    private static final double[] SAMPLE_X = {0.0, 0.001, 0.05, 0.25, 0.5, 0.75, 0.95, 0.999, 1.0};
    private static final float RANGE = 1000; // Beta's k = range * beta, so this must be representative

    public static void main(String[] args) {
        checkRoundTrip("Gamma", new FITSViewState.Data(
                FITSViewState.ClippingMode.ZScale, 4, 0, 0, FITSViewState.ScalingMode.Gamma, 1 / 2.2, 0, 0));
        checkRoundTrip("Beta (asinh)", new FITSViewState.Data(
                FITSViewState.ClippingMode.ZScale, 4, 0, 0, FITSViewState.ScalingMode.Beta, 0, 1. / 64, 0));
        checkRoundTrip("Alpha (log1p)", new FITSViewState.Data(
                FITSViewState.ClippingMode.ZScale, 4, 0, 0, FITSViewState.ScalingMode.Alpha, 0, 0, 1000));

        // The colorbar hover math (Colorbar.physicalValueText / ImageBuffer.PhysicalScale) also
        // depends on this outer layer: normalized-domain x -> physical min..max.
        float min = -50, max = 450;
        var scale = new ImageBuffer.PhysicalScale(min, max, y -> y, "Y = t"); // identity inverse for this check
        assertClose("PhysicalScale at 0", min, scale.toPhysical(0), 1e-6);
        assertClose("PhysicalScale at 1", max, scale.toPhysical(1), 1e-6);
        assertClose("PhysicalScale at 0.5", (min + max) / 2, scale.toPhysical(0.5), 1e-6);

        System.out.println("FITSImageInverseCheck: PASS");
    }

    private static void checkRoundTrip(String label, FITSViewState.Data state) {
        DoubleUnaryOperator forward = FITSImage.normalizedMapping(state, RANGE);
        DoubleUnaryOperator inverse = FITSImage.inverseMapping(state, RANGE);
        for (double x : SAMPLE_X) {
            double stretched = forward.applyAsDouble(x);
            double recovered = inverse.applyAsDouble(stretched);
            assertClose(label + ": round-trip at x=" + x, x, recovered, 1e-6);
        }
    }

    private static void assertClose(String what, double expected, double actual, double tol) {
        if (Math.abs(expected - actual) > tol)
            throw new AssertionError(what + ": expected " + expected + ", got " + actual);
    }

    private FITSImageInverseCheck() {}
}
