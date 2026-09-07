package org.helioviewer.jhv.display;

/**
 * The HDR export's colour maths, which is the part nobody can eyeball. A file that is a stop too
 * bright, or that skips the HLG OOTF, or that tags BT.2020 without converting the primaries, looks
 * plausible on the screen it was made on and wrong everywhere else.
 *
 * <p>So it is pinned against the numbers the standards give, not against itself: diffuse white
 * lands on HLG 0.75 and PQ 0.5806, both of which are published values for 203 cd/m2, and the
 * primaries matrix takes white to white.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.display.HdrExportCheck
 */
public final class HdrExportCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    /** The shader's encode, so a test can start from a known display-referred value. */
    private static double srgb(double linear) {
        return linear <= 0.0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
    }

    private static double[] encoded(double linear, HdrTransfer.Curve curve) {
        double[] px = {srgb(linear), srgb(linear), srgb(linear)};
        HdrTransfer.encode(px, curve);
        return px;
    }

    public static void main(String[] args) {
        // 1. The shader's encode and this decode are inverses, including past 1 where the whole
        //    point lies: an HDR gain of 4 leaves values up to 4 in the buffer.
        double worst = 0;
        for (double lin = 0; lin <= 4.0001; lin += 0.01)
            worst = Math.max(worst, Math.abs(HdrTransfer.linearize(srgb(lin)) - lin));
        expect(String.format("the shader's encode round-trips through linearize, to 4x white (worst %.2e)", worst), worst < 1e-9);

        // 2. Diffuse white. BT.2408 puts it at 203 cd/m2, and both standards say where that lands.
        double[] hlgWhite = encoded(1, HdrTransfer.Curve.HLG);
        expect(String.format("SDR white is HLG 0.75, the published value for 203 cd/m2 (got %.4f)", hlgWhite[0]),
                Math.abs(hlgWhite[0] - 0.75) < 0.002);
        double[] pqWhite = encoded(1, HdrTransfer.Curve.PQ);
        expect(String.format("and PQ 0.5806, the published value for the same (got %.4f)", pqWhite[0]),
                Math.abs(pqWhite[0] - 0.5806) < 0.002);

        // 3. The gain reaches the file. Four times white is four times the luminance, 812 cd/m2,
        //    and both curves have to place it above white and below their ceiling.
        double[] hlg4 = encoded(4, HdrTransfer.Curve.HLG);
        double[] pq4 = encoded(4, HdrTransfer.Curve.PQ);
        expect(String.format("4x white is above white and inside the range on HLG (%.4f)", hlg4[0]),
                hlg4[0] > hlgWhite[0] + 0.1 && hlg4[0] < 1);
        expect(String.format("and on PQ (%.4f)", pq4[0]), pq4[0] > pqWhite[0] + 0.05 && pq4[0] < 1);
        expect("PQ places 812 cd/m2 where the inverse EOTF says it should",
                Math.abs(pq4[0] - HdrTransfer.pq(4 * HdrTransfer.REFERENCE_WHITE / 10000)) < 1e-9);

        // 4. Neutral stays neutral: the primaries matrix takes BT.709 white to BT.2020 white, and
        //    a colour cast here would tint the whole export.
        for (double lin : new double[]{0.2, 1, 3}) {
            double[] px = encoded(lin, HdrTransfer.Curve.HLG);
            expect(String.format("grey at %.1fx white stays grey (%.5f, %.5f, %.5f)", lin, px[0], px[1], px[2]),
                    Math.abs(px[0] - px[1]) < 1e-6 && Math.abs(px[1] - px[2]) < 1e-6);
        }

        // 5. Monotone and bounded over the whole range either curve will ever see.
        for (HdrTransfer.Curve curve : new HdrTransfer.Curve[]{HdrTransfer.Curve.HLG, HdrTransfer.Curve.PQ}) {
            double last = -1;
            boolean rises = true, bounded = true;
            for (double lin = 0; lin <= 16.0001; lin += 0.05) {
                double v = encoded(lin, curve)[0];
                rises &= v >= last;
                bounded &= v >= 0 && v <= 1;
                last = v;
            }
            expect(curve + " is monotone from black to 16x white", rises);
            expect(curve + " stays inside [0, 1], so the 16-bit frame holds it exactly", bounded);
        }

        // 6. Black is black. A lift here would grey out the sky in every exported frame. PQ's
        //    floor is not exactly zero: its inverse EOTF at zero luminance is (c1)^m2, about
        //    7e-7, which is a fifth of a sixteen-bit code and a thousandth of a ten-bit one. That
        //    is the curve's own definition, not a mistake, so the bound is half a code rather
        //    than equality.
        expect("black encodes to zero on HLG", encoded(0, HdrTransfer.Curve.HLG)[0] == 0);
        expect(String.format("and to under half a 16-bit code on PQ (%.1e)", encoded(0, HdrTransfer.Curve.PQ)[0]),
                encoded(0, HdrTransfer.Curve.PQ)[0] < 0.5 / 65535);

        // 7. A saturated colour keeps its hue order through the primaries change.
        double[] red = {srgb(1), 0, 0};
        HdrTransfer.encode(red, HdrTransfer.Curve.HLG);
        expect(String.format("red stays red through BT.2087 (%.4f, %.4f, %.4f)", red[0], red[1], red[2]),
                red[0] > red[1] && red[1] >= red[2] && red[0] > 0.5);

        if (failures > 0)
            throw new AssertionError(failures + " HDR export failure(s)");
        System.out.println("HdrExportCheck: PASS");
    }

}
