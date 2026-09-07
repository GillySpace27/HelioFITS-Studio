package org.helioviewer.jhv.display;

/**
 * The rendered picture as an HDR video signal.
 *
 * <p>What the scene shader writes is display-referred and sRGB-encoded, extended past 1.0 through
 * the magnitude, where 1.0 is the interface white of the screen and the HDR gain has already put
 * the corona above it. That is exactly what the Metal presenter decodes to show on an EDR display,
 * and until now the capture simply clamped it at 1: everything the headroom was carrying was
 * thrown away at the last step, which is why an exported movie looked blown out where the screen
 * looked right.
 *
 * <p>Here that same signal is turned into one a player understands. Three steps, in order, none of
 * them optional:
 *
 * <ol>
 * <li><b>Linearize</b>, undoing the shader's encode, so the arithmetic is on light.
 * <li><b>Primaries</b>: the render is in BT.709 primaries and an HDR file is delivered in BT.2020.
 *     Tagging without converting would leave every colour oversaturated, so the linear RGB goes
 *     through the BT.2087 matrix.
 * <li><b>Absolute luminance</b>: 1.0 is diffuse white, which ITU-R BT.2408 puts at 203 cd/m2 in an
 *     HDR programme. A gain of 4 therefore lands at 812 cd/m2, and that number is what makes the
 *     file mean the same thing on every display rather than "as bright as this laptop was".
 * </ol>
 *
 * <p>Then the curve. PQ is display-referred already, so it is one function of luminance. HLG is
 * scene-referred, so display light has to go back through the inverse of its OOTF first: without
 * that the picture is contrastier than intended on an HLG display, by exactly the system gamma.
 * The pair of them is checked in extra/test/HdrExportCheck.java, which pins diffuse white at the
 * two values the standards give for it, HLG 0.75 and PQ 0.5806.
 */
public final class HdrTransfer {

    public enum Curve {
        /** Clamp at interface white, which is what an SDR file can hold. */
        NONE,
        /** ITU-R BT.2100 hybrid log-gamma. Degrades sanely on a player that ignores the tagging. */
        HLG,
        /** ITU-R BT.2100 perceptual quantizer. Absolute nits, flat and dark if nothing tone-maps it. */
        PQ
    }

    /** Set while an HDR export is recording; read by the frame capture. Not a display setting. */
    public static volatile Curve capture = Curve.NONE;

    /** ITU-R BT.2408: diffuse white sits at 203 cd/m2 in an HDR programme. */
    public static final double REFERENCE_WHITE = 203;

    /** The HLG system this encodes for. The OOTF gamma below is 1.2 because of this number. */
    private static final double HLG_PEAK = 1000;
    private static final double HLG_GAMMA = 1.2; // 1.2 + 0.42 log10(peak / 1000), which is 1.2 at 1000
    private static final double HLG_A = 0.17883277, HLG_B = 0.28466892, HLG_C = 0.55991073;

    /** PQ's own ceiling, by definition. */
    private static final double PQ_PEAK = 10000;
    private static final double PQ_M1 = 2610 / 16384., PQ_M2 = 2523 / 4096. * 128;
    private static final double PQ_C1 = 3424 / 4096., PQ_C2 = 2413 / 4096. * 32, PQ_C3 = 2392 / 4096. * 32;

    /** ITU-R BT.2087, BT.709 to BT.2020 primaries in linear light. */
    private static final double[][] TO_2020 = {
            {0.6274039, 0.3292830, 0.0433131},
            {0.0690797, 0.9195400, 0.0113803},
            {0.0163914, 0.0880132, 0.8955953}};

    /** BT.2020 luminance weights, for the HLG OOTF, which acts on luminance and not per channel. */
    private static final double[] Y_2020 = {0.2627, 0.6780, 0.0593};

    /** The shader's encode, undone: sRGB extended past 1 through the magnitude. */
    public static double linearize(double v) {
        double a = Math.abs(v);
        double lin = a <= 0.04045 ? a / 12.92 : Math.pow((a + 0.055) / 1.055, 2.4);
        return v < 0 ? -lin : lin;
    }

    /**
     * One pixel in place: the shader's three encoded components in, the encoder's three signal
     * values in [0, 1] out. Alpha is not touched; the canvas is composited over black already.
     */
    public static void encode(double[] rgb, Curve curve) {
        double r = linearize(rgb[0]), g = linearize(rgb[1]), b = linearize(rgb[2]);
        for (int i = 0; i < 3; i++)
            rgb[i] = Math.max(0, TO_2020[i][0] * r + TO_2020[i][1] * g + TO_2020[i][2] * b) * REFERENCE_WHITE;

        if (curve == Curve.PQ) {
            for (int i = 0; i < 3; i++)
                rgb[i] = pq(rgb[i] / PQ_PEAK);
            return;
        }
        // HLG. The OETF wants scene light; what we have is display light, so undo the OOTF first.
        // Y_d = peak * Y_s^gamma, and RGB_s = (RGB_d / peak) * (Y_d / peak)^((1 - gamma) / gamma).
        double y = Y_2020[0] * rgb[0] + Y_2020[1] * rgb[1] + Y_2020[2] * rgb[2];
        double scale = y <= 0 ? 0 : Math.pow(Math.min(y / HLG_PEAK, 1), (1 - HLG_GAMMA) / HLG_GAMMA);
        for (int i = 0; i < 3; i++)
            rgb[i] = hlg(Math.clamp(rgb[i] / HLG_PEAK * scale, 0, 1));
    }

    /** ITU-R BT.2100 HLG OETF, scene light in [0, 1] to signal. */
    static double hlg(double e) {
        return e <= 1 / 12. ? Math.sqrt(3 * e) : HLG_A * Math.log(12 * e - HLG_B) + HLG_C;
    }

    /** ITU-R BT.2100 PQ inverse EOTF, luminance as a fraction of 10000 cd/m2 to signal. */
    static double pq(double y) {
        double p = Math.pow(Math.clamp(y, 0, 1), PQ_M1);
        return Math.pow((PQ_C1 + PQ_C2 * p) / (1 + PQ_C3 * p), PQ_M2);
    }

    private HdrTransfer() {}

}
