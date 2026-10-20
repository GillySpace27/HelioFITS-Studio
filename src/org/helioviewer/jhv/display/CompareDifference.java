package org.helioviewer.jhv.display;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

import javax.annotation.Nullable;

import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.time.TimeUtils;

/**
 * The Compare palette's Difference mode (HS-11): layer A minus layer B in the data's own units,
 * with each layer's stretch inverted first, drawn through the running difference's shader path
 * (layer B in the difference texture, its own geometry in images[1]).
 *
 * <p>Pure: it decides whether A and B can be subtracted and with which coefficients, and says why
 * not or what to keep in mind, so a check can pin it without a GL context. It refuses rather than
 * subtracting silently when the two cannot mean the same thing (no physical units, a filter or a
 * difference of their own, different instruments or units, a stretch the shader cannot invert);
 * it subtracts and says so when only the size, the product or the time differ.
 *
 * <p>The picture is not a calibrated product: mid grey is A = B, white and black are A minus B at
 * plus and minus {@link Verdict#halfRange} or beyond, the same fifth of the range at which the
 * running difference saturates (imageCommon.frag, BOOST).
 */
public final class CompareDifference {

    /** A difference of this share of the larger layer's display range reaches white (or black). */
    static final double SATURATION = 0.2;

    /** What the shader reads in isDiff for this mode; DifferenceMode's ordinals take 0 to 2. */
    public static final int SHADER_CODE = 3;

    /**
     * One layer as the verdict sees it.
     *
     * @param filter        the processing filter's name, "None" when there is none
     * @param ownDifference the layer shows its own running or base difference
     */
    public record Side(String name, String instrument, String unit, @Nullable ImageBuffer.PhysicalScale scale,
                       int width, int height, long milli, String filter, boolean ownDifference) {}

    /**
     * @param refusal      why A minus B is not drawn, or null when it is
     * @param notes        what differs between A and B and is worth saying, when it is drawn
     * @param coefficients for the shader: scale of A, scale of B, offset, exponent of A, exponent of B
     * @param halfRange    the physical difference drawn as white (and its negative as black)
     * @param unit         the unit both files state, empty when neither does
     */
    public record Verdict(@Nullable String refusal, List<String> notes, float[] coefficients, double halfRange, String unit) {
        public boolean drawn() {
            return refusal == null;
        }
    }

    private CompareDifference() {}

    static Verdict refuse(String why) {
        return new Verdict(why, List.of(), new float[5], 0, "");
    }

    public static Verdict judge(Side a, Side b) {
        for (Side s : List.of(a, b)) {
            String who = (s == a ? "A (" : "B (") + s.name() + ")";
            if (s.scale() == null)
                return refuse(who + " has no physical units: only FITS layers carry them");
            if ("RHEF".equals(s.filter()))
                return refuse(who + " has RHEF on: RHEF output is a visualization, not a calibrated radiance");
            if (!"None".equals(s.filter()))
                return refuse(who + " has the " + s.filter() + " filter on, which changes the values");
            if (s.ownDifference())
                return refuse(who + " shows its own running or base difference; set it to None first");
        }
        String ia = a.instrument().trim(), ib = b.instrument().trim();
        if (!ia.isEmpty() && !ib.isEmpty() && !ia.equalsIgnoreCase(ib))
            return refuse("A is " + ia + " and B is " + ib + ": different instruments are not subtracted");
        String ua = a.unit().trim(), ub = b.unit().trim();
        if (!ua.equals(ub)) { // case-sensitive: in FITS units MW and mW are different units
            if (ua.isEmpty() || ub.isEmpty())
                return refuse("Only " + (ua.isEmpty() ? "B" : "A") + " states its unit (BUNIT: "
                        + (ua.isEmpty() ? ub : ua) + "), so the two cannot be checked to match");
            return refuse("Units differ: A in " + ua + ", B in " + ub);
        }

        ImageBuffer.PhysicalScale sa = a.scale(), sb = b.scale();
        double pa = powerExponent(sa.inverseStretch()), pb = powerExponent(sb.inverseStretch());
        if (Double.isNaN(pa))
            return refuse("A uses the stretch " + sa.stretch() + ", which cannot be undone here; choose Gamma in its FITS settings");
        if (Double.isNaN(pb))
            return refuse("B uses the stretch " + sb.stretch() + ", which cannot be undone here; choose Gamma in its FITS settings");

        List<String> notes = new ArrayList<>();
        if (ua.isEmpty())
            notes.add("Neither file states its unit (BUNIT): the difference is in the files' own numbers");
        if (!a.name().equals(b.name()))
            notes.add("A is " + a.name() + ", B is " + b.name());
        if (a.width() != b.width() || a.height() != b.height())
            notes.add("Sizes differ: A " + a.width() + " x " + a.height() + ", B " + b.width() + " x " + b.height()
                    + " pixels; B is sampled onto A's view");
        long dt = b.milli() - a.milli();
        if (dt != 0)
            notes.add("B is " + TimeUtils.formatDurationSig(Math.abs(dt)) + (dt > 0 ? " after A" : " before A"));

        double spanA = (double) sa.max() - sa.min(), spanB = (double) sb.max() - sb.min();
        double half = SATURATION * Math.max(spanA, spanB);
        double k = 0.5 / half;
        float[] c = {(float) (spanA * k), (float) (spanB * k), (float) (((double) sa.min() - sb.min()) * k + 0.5),
                (float) pa, (float) pb};
        return new Verdict(null, List.copyOf(notes), c, half, ua);
    }

    /**
     * The shader's value for stored fractions {@code fa} of A and {@code fb} of B, with these
     * coefficients: the CPU mirror of imageCommon.frag's Compare branch, for the check.
     */
    static double shaderValue(float[] c, double fa, double fb) {
        return linearize(fa, c[3]) * c[0] - linearize(fb, c[4]) * c[1] + c[2];
    }

    private static double linearize(double f, double p) {
        return f > 1 ? f : Math.pow(f, p); // above 1 the decoder stored the linear ratio itself
    }

    private static final double[] PROBES = {0.05, 0.25, 0.75, 0.95};

    /**
     * The exponent p for which this inverse stretch is y to the power p on [0, 1] (1 for linear,
     * 1 / gamma for Gamma), or NaN when it is not a power law (asinh, log), which the shader does
     * not undo. Found at y = 0.5, then confirmed at other points.
     */
    static double powerExponent(DoubleUnaryOperator inverse) {
        double mid = inverse.applyAsDouble(0.5);
        if (!(mid > 0 && mid < 1))
            return Double.NaN;
        double p = Math.log(mid) / Math.log(0.5);
        for (double y : PROBES) {
            double want = Math.pow(y, p), got = inverse.applyAsDouble(y);
            if (!(Math.abs(got - want) <= 1e-9 * Math.max(1, Math.abs(want))))
                return Double.NaN;
        }
        return p;
    }

}
