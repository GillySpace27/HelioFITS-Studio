package org.helioviewer.jhv.display;

import org.helioviewer.jhv.image.lut.LUT;

/**
 * The HDR mapping maths of solarCommon.frag, ported line for line, so the claims made about each
 * mode are tested instead of asserted.
 *
 * <p>The failure this exists to catch already happened: the roll-to-white was guarded by
 * hdrMode >= 2, which also caught Uniform (ordinal 4). Uniform's expansion is largest exactly
 * where the roll's mix factor is largest, so it desaturated hardest at the low end and rendered as
 * a grey copy of Linear. Nothing in a movie says "this mode lost its colour on purpose or by
 * accident"; a chromaticity comparison does.
 *
 * <p>It also pins the honest limits of the other modes: Linear and the knees map everything at and
 * above the top of the display range to one output, which is why the legend's over-range section
 * is a single flat block under them, and is the thing Uniform and Beyond-range do not do.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.display.HdrMappingCheck
 */
public final class HdrMappingCheck {

    private static int failures;

    private static final int LINEAR = 0, HARD_KNEE = 1, SOFT_KNEE = 2, BEYOND = 3, UNIFORM = 4;

    /** solarCommon.frag, from the sRGB decode to the last lin *= ..., in linear light. */
    private static double[] map(double[] rgb8, double value, int mode, double gain, double knee) {
        double[] lin = new double[3];
        for (int i = 0; i < 3; i++) {
            double c = rgb8[i] / 255.;
            lin[i] = c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        }
        double e = gain;
        if (mode == BEYOND)
            e = Math.clamp(value, 1, gain);
        else if (mode == UNIFORM) {
            double lMax = 116 * Math.cbrt(gain) - 16;
            double lt = value <= 1 ? 100 * Math.max(value, 0)
                    : 100 + (lMax - 100) * Math.min((value - 1) / Math.max(gain - 1, 1e-4), 1);
            double yt = lt > 8 ? Math.pow((lt + 16) / 116, 3) : lt / 903.3;
            double y0 = luminance(lin);
            e = y0 > 1e-6 ? yt / y0 : 1;
        } else if (mode != LINEAR) {
            double t = Math.clamp((Math.clamp(value, 0, 1) - knee) / (1 - knee), 0, 1);
            e = mode == HARD_KNEE ? 1 + t * (gain - 1) : 1 + (gain - 1) * t * t;
        }
        for (int i = 0; i < 3; i++)
            lin[i] *= e;
        if (mode == SOFT_KNEE || mode == BEYOND) {
            double y = luminance(lin);
            double m = (e - 1) / Math.max(gain - 1, 1e-4);
            for (int i = 0; i < 3; i++)
                lin[i] = lin[i] * (1 - m) + y * m;
        }
        return lin;
    }

    private static double luminance(double[] lin) {
        return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2];
    }

    /** CIE L*, continued past 100 the way the extended range does. */
    private static double lStar(double y) {
        return y > 0.008856 ? 116 * Math.cbrt(y) - 16 : 903.3 * y;
    }

    /** How far two colours are apart in chromaticity alone, luminance divided out. */
    private static double hueShift(double[] a, double[] b) {
        double ya = luminance(a), yb = luminance(b);
        if (ya < 1e-9 || yb < 1e-9)
            return 0;
        double worst = 0;
        for (int i = 0; i < 3; i++)
            worst = Math.max(worst, Math.abs(a[i] / ya - b[i] / yb));
        return worst;
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    /** The value the colour table gives at v, clamped at both ends as the LUT texture is. */
    private static double[] sample(int[] argb, double v) {
        int i = Math.clamp((int) Math.round(v * (argb.length - 1)), 0, argb.length - 1);
        int p = argb[i];
        return new double[]{(p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF};
    }

    public static void main(String[] args) {
        LUT punch = LUT.get("PUNCH");
        if (punch == null)
            throw new AssertionError("the PUNCH colour table is missing; run with resources on the classpath");
        int[] table = punch.lut8();
        double gain = 4, knee = 0.75;

        // 1. Hue. Uniform scales luminance and must leave chromaticity exactly where the table put
        //    it; so must Linear and the hard knee. Soft knee and beyond-range roll to white by
        //    design, and have to actually do it or the design is a comment and nothing else.
        double[] saturated = sample(table, 0.5); // (161, 107, 27): the table's most coloured region
        for (double v : new double[]{0.25, 0.5, 0.75, 0.95}) {
            expect(String.format("Uniform keeps the hue at v=%.2f", v),
                    hueShift(map(saturated, v, UNIFORM, gain, knee), map(saturated, v, LINEAR, 1, knee)) < 1e-9);
        }
        expect("the hard knee keeps the hue at the top of the range",
                hueShift(map(saturated, 1, HARD_KNEE, gain, knee), map(saturated, 1, LINEAR, 1, knee)) < 1e-9);
        expect("the soft knee rolls to white at the top of the range, as intended",
                hueShift(map(saturated, 1, SOFT_KNEE, gain, knee), map(saturated, 1, LINEAR, 1, knee)) > 0.1);
        expect("beyond-range rolls to white at the top of the headroom, as intended",
                hueShift(map(saturated, gain, BEYOND, gain, knee), map(saturated, 1, LINEAR, 1, knee)) > 0.1);

        // 2. Uniform is linear in L* inside the range, whatever the table's own lightness did.
        //    A gamma-2 ramp is a table that is anything but: it spends its bottom half under L* 46.
        int[] bent = new int[256];
        for (int i = 0; i < 256; i++) {
            int c = (int) Math.round(255 * Math.pow(i / 255., 2));
            bent[i] = (c << 16) | ((int) Math.round(c * 0.8) << 8) | (int) Math.round(c * 0.5);
        }
        double worstIn = 0, worstBent = 0;
        for (double v = 0.05; v <= 1.0001; v += 0.05) {
            worstIn = Math.max(worstIn, Math.abs(lStar(luminance(map(sample(table, v), v, UNIFORM, gain, knee))) - 100 * v));
            worstBent = Math.max(worstBent, Math.abs(lStar(luminance(map(sample(bent, v), v, UNIFORM, gain, knee))) - 100 * v));
        }
        expect(String.format("Uniform is L* = 100v across the range on PUNCH (worst error %.2f L*)", worstIn), worstIn < 1.5);
        expect(String.format("and on a table that is far from lightness-linear (worst error %.2f L*)", worstBent), worstBent < 1.5);
        expect("which leaves an already-linear table alone: Uniform at v=0.5 is the plain table colour",
                Math.abs(luminance(map(sample(table, 0.5), 0.5, UNIFORM, gain, knee))
                        - luminance(map(sample(table, 0.5), 0.5, LINEAR, 1, knee))) < 0.01);

        // 3. Over the range. Every mode but Uniform and beyond-range maps v = 1, 2 and gain to one
        //    output, which is the flat block at the right-hand end of the legend.
        for (int mode : new int[]{LINEAR, HARD_KNEE, SOFT_KNEE}) {
            double a = luminance(map(sample(table, 1), 1, mode, gain, knee));
            double b = luminance(map(sample(table, 2), 2, mode, gain, knee));
            double c = luminance(map(sample(table, gain), gain, mode, gain, knee));
            expect("mode " + mode + " pins everything at and above the top of the range to one output",
                    Math.abs(a - b) < 1e-12 && Math.abs(b - c) < 1e-12);
        }
        double last = -1;
        boolean climbs = true;
        for (double v = 1; v <= gain + 1e-9; v += 0.1) {
            double l = lStar(luminance(map(sample(table, v), v, UNIFORM, gain, knee)));
            climbs &= l > last + 0.5;
            last = l;
        }
        expect("Uniform keeps climbing across the whole over-range section, in steps of at least 0.5 L*", climbs);
        expect(String.format("reaching the display's peak at v = gain (L* %.1f)", last),
                Math.abs(last - (116 * Math.cbrt(gain) - 16)) < 1.5);
        expect("and the luminance there is the gain itself, so nothing asks the panel for more than it has",
                Math.abs(luminance(map(sample(table, gain), gain, UNIFORM, gain, knee)) - gain) < 0.02);

        // 4. The headroom is spent above the range, not inside it: in-range Uniform is the picture
        //    with no headroom at all. That is the trade, and it should be visible in the numbers.
        expect("Uniform inside the range is the plain SDR picture (v=0.8)",
                Math.abs(luminance(map(sample(table, 0.8), 0.8, UNIFORM, gain, knee))
                        - luminance(map(sample(table, 0.8), 0.8, LINEAR, 1, knee))) < 0.01);
        expect("while Linear at the same v is gain times brighter",
                Math.abs(luminance(map(sample(table, 0.8), 0.8, LINEAR, gain, knee))
                        / luminance(map(sample(table, 0.8), 0.8, LINEAR, 1, knee)) - gain) < 0.01);

        if (failures > 0)
            throw new AssertionError(failures + " HDR mapping failure(s)");
        System.out.println("HdrMappingCheck: PASS");
    }

}
