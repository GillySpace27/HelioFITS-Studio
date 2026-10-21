package org.helioviewer.jhv.display;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.helioviewer.jhv.image.ImageBuffer;
import org.json.JSONObject;

/**
 * The Compare palette's Difference (HS-11) subtracts in the files' units and refuses what it must.
 *
 * <p>The shader draws (A minus B) as linearize(fa) * sA - linearize(fb) * sB + c, where the
 * fractions are what the decoder stored (after its stretch) and the coefficients come from
 * CompareDifference.judge. Pinned here against ImageBuffer.PhysicalScale.toPhysical, the same
 * inverse the value readout uses: for a linear and a Gamma stretch, inside and above the range, the
 * shader's value is (physical A minus physical B) / (2 halfRange) + 0.5. Also pinned: an asinh or
 * log stretch, missing units, a filter, a layer's own difference, different instruments or units
 * are refused; a different size or time is drawn and said; the mode round-trips through a session;
 * the GLSL code and the DisplayBlock's size agree with the Java side.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.display.CompareDifferenceCheck
 */
public final class CompareDifferenceCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static ImageBuffer.PhysicalScale gamma(float min, float max, double g) {
        return new ImageBuffer.PhysicalScale(min, max, y -> Math.pow(y, 1 / g), g == 1 ? "Y = t" : "Y = t^" + g, x -> Math.pow(x, g));
    }

    private static CompareDifference.Side side(String name, String unit, ImageBuffer.PhysicalScale scale) {
        return new CompareDifference.Side(name, "AIA", unit, scale, 1024, 1024, 0, "None", false);
    }

    private static CompareDifference.Side with(CompareDifference.Side s, String instrument, String unit, String filter,
                                               boolean ownDifference, int width, long milli) {
        return new CompareDifference.Side(s.name(), instrument, unit, s.scale(), width, width, milli, filter, ownDifference);
    }

    /** Over the stored fractions fa, fb: the shader's value against the readout's physical numbers. */
    private static double worstError(CompareDifference.Verdict v, ImageBuffer.PhysicalScale sa, ImageBuffer.PhysicalScale sb) {
        double worst = 0;
        for (double fa : new double[]{0.01, 0.2, 0.5, 0.83, 1, 1.7})
            for (double fb : new double[]{0.03, 0.4, 0.66, 1, 2.5}) {
                double want = (sa.toPhysical(fa) - sb.toPhysical(fb)) / (2 * v.halfRange()) + 0.5;
                worst = Math.max(worst, Math.abs(CompareDifference.shaderValue(v.coefficients(), fa, fb) - want));
            }
        return worst;
    }

    public static void main(String[] args) throws Exception {
        expect("a linear stretch inverts with exponent 1", CompareDifference.powerExponent(y -> y) == 1);
        double p = CompareDifference.powerExponent(gamma(0, 1, 1 / 2.2).inverseStretch());
        expect("the default Gamma 1/2.2 inverts with exponent 2.2, got " + p, Math.abs(p - 2.2) < 1e-9);
        double k = 40, asinhK = Math.log(k + Math.sqrt(k * k + 1));
        DoubleUnaryOperator beta = y -> Math.sinh(y * asinhK) / k;
        DoubleUnaryOperator alpha = y -> Math.expm1(y * Math.log1p(1000)) / 1000;
        expect("an asinh (Beta) or log (Alpha) stretch is not a power law",
                Double.isNaN(CompareDifference.powerExponent(beta)) && Double.isNaN(CompareDifference.powerExponent(alpha)));

        // Linear on both, different ranges: 500 DN minus 300 DN, white at a fifth of the larger range (200 DN).
        ImageBuffer.PhysicalScale la = gamma(0, 1000, 1), lb = gamma(100, 600, 1);
        CompareDifference.Verdict v = CompareDifference.judge(side("AIA 171", "DN", la), side("AIA 171", "DN", lb));
        expect("two linear DN layers are drawn: " + v.refusal(), v.drawn());
        expect("white stands for 200 DN (a fifth of the larger range), got " + v.halfRange(), Math.abs(v.halfRange() - 200) < 1e-9);
        expect("500 DN minus 300 DN is white (1.0), got " + CompareDifference.shaderValue(v.coefficients(), 0.5, 0.4),
                Math.abs(CompareDifference.shaderValue(v.coefficients(), 0.5, 0.4) - 1) < 1e-6);
        expect("300 DN minus 300 DN is grey (0.5), got " + CompareDifference.shaderValue(v.coefficients(), 0.3, 0.4),
                Math.abs(CompareDifference.shaderValue(v.coefficients(), 0.3, 0.4) - 0.5) < 1e-6);
        expect("linear: the shader matches PhysicalScale.toPhysical, worst " + worstError(v, la, lb), worstError(v, la, lb) < 1e-5);
        expect("the same product at the same time and size has no notes: " + v.notes(), v.notes().isEmpty());

        // Gamma 1/2.2 against 1/1.5, the readout's own inverse as the reference.
        ImageBuffer.PhysicalScale ga = gamma(-50, 2000, 1 / 2.2), gb = gamma(10, 1500, 1 / 1.5);
        v = CompareDifference.judge(side("AIA 171", "DN", ga), side("AIA 171", "DN", gb));
        expect("Gamma on both is drawn: " + v.refusal(), v.drawn());
        expect("Gamma: the shader matches PhysicalScale.toPhysical, inside and above the range, worst " + worstError(v, ga, gb),
                worstError(v, ga, gb) < 1e-5);

        // Refusals, each with its reason.
        CompareDifference.Side a = side("AIA 171", "DN", la), b = side("AIA 171", "DN", lb);
        record Case(String what, CompareDifference.Side a, CompareDifference.Side b, String says) {}
        for (Case c : List.of(
                new Case("no physical units", a, new CompareDifference.Side("AIA 171", "AIA", "DN", null, 1024, 1024, 0, "None", false), "no physical units"),
                new Case("RHEF on B", a, with(b, "AIA", "DN", "RHEF", false, 1024, 0), "not a calibrated radiance"),
                new Case("MGN on A", with(a, "AIA", "DN", "MGN", false, 1024, 0), b, "MGN"),
                new Case("A's own running difference", with(a, "AIA", "DN", "None", true, 1024, 0), b, "running or base difference"),
                new Case("different instruments", a, with(b, "SUVI", "DN", "None", false, 1024, 0), "different instruments"),
                new Case("different units", a, with(b, "AIA", "DN/s", "None", false, 1024, 0), "Units differ"),
                new Case("one unit missing", a, with(b, "AIA", "", "None", false, 1024, 0), "Only A states"),
                new Case("units that differ only in case (MW and mW)", with(a, "AIA", "MW", "None", false, 1024, 0), with(b, "AIA", "mW", "None", false, 1024, 0), "Units differ"),
                new Case("an asinh stretch", a, new CompareDifference.Side("AIA 171", "AIA", "DN",
                        new ImageBuffer.PhysicalScale(0, 1, beta, "Y = asinh(k t) / asinh(k), k = 40"), 1024, 1024, 0, "None", false), "choose Gamma"))) {
            CompareDifference.Verdict r = CompareDifference.judge(c.a(), c.b());
            expect("refused, " + c.what() + ": " + r.refusal(), !r.drawn() && r.refusal().contains(c.says()));
        }

        // Said, not refused.
        v = CompareDifference.judge(with(a, "AIA", "", "None", false, 4096, 0), new CompareDifference.Side("AIA 193", "AIA", "", lb, 1024, 1024, 720_000, "None", false));
        expect("no unit on either, another size, product and time is drawn: " + v.refusal(), v.drawn());
        String notes = String.join(" | ", v.notes());
        expect("and says so: " + notes, notes.contains("Neither file states its unit") && notes.contains("Sizes differ")
                && notes.contains("A is AIA 171, B is AIA 193") && notes.contains("B is 12.0 min after A"));

        // Session: Difference round-trips, and an older build's absence of it is not this build's concern.
        Compare.setMode(Compare.Mode.Difference);
        JSONObject saved = Compare.toJson();
        expect("Difference is saved as its mode: " + saved, saved != null && "Difference".equals(saved.optString("mode")));
        Compare.setMode(Compare.Mode.Off);
        Compare.fromJson(saved, List.of());
        expect("and restored", Compare.getMode() == Compare.Mode.Difference);
        expect("with no layers there is no verdict and nothing is hidden", Compare.differenceVerdict() == null
                && Compare.differenceFor(null) == null);
        Compare.setMode(Compare.Mode.Off);

        // The GLSL side agrees with the Java side.
        String frag = Files.readString(Path.of("resources/glsl/imageCommon.frag"));
        Matcher code = Pattern.compile("#define COMPAREDIFFERENCE (\\d+)\\.").matcher(frag);
        expect("imageCommon.frag's COMPAREDIFFERENCE is SHADER_CODE " + CompareDifference.SHADER_CODE,
                code.find() && Integer.parseInt(code.group(1)) == CompareDifference.SHADER_CODE);
        int std140 = displayBlockFloats(frag);
        Class<?> layout = Class.forName("org.helioviewer.jhv.opengl.UniformBlockLayout");
        Object display = null;
        for (Object constant : layout.getEnumConstants()) // the enum is package-private: no valueOf from here
            if (((Enum<?>) constant).name().equals("DISPLAY"))
                display = constant;
        java.lang.reflect.Field count = layout.getDeclaredField("floatCount");
        count.setAccessible(true);
        int declared = count.getInt(display);
        expect("UniformBlockLayout.DISPLAY (" + declared + " floats) is the shader's DisplayBlock (" + std140 + ")", declared == std140);

        System.out.println(failures == 0 ? "CompareDifferenceCheck: ok" : "CompareDifferenceCheck: " + failures + " FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** The std140 size of DisplayBlock in floats, rounded up to a vec4: float 1, vec2 2 (aligned 2), vec3 and vec4 aligned 4. */
    private static int displayBlockFloats(String frag) {
        int start = frag.indexOf("uniform DisplayBlock {");
        String body = frag.substring(start, frag.indexOf("} display;", start));
        Matcher m = Pattern.compile("(?m)^\\s*(float|vec2|vec3|vec4)\\s+\\w+;").matcher(body);
        int offset = 0;
        while (m.find()) {
            int size = switch (m.group(1)) {
                case "float" -> 1;
                case "vec2" -> 2;
                case "vec3" -> 3;
                default -> 4;
            };
            int align = size == 1 ? 1 : size == 2 ? 2 : 4;
            offset = (offset + align - 1) / align * align + size;
        }
        return (offset + 3) / 4 * 4;
    }
}
