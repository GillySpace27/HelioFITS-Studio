package org.helioviewer.jhv.image;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * FilterRHEF ranks the way sunkit-image's RHEF ranks.
 *
 * <p>The vectors in extra/test/data/rhef-conformance were made once by
 * extra/test/rhef/make_vectors.py from sunkit-image (its version is in manifest.json): FilterRHEF's
 * annuli, sunkit-image's rank rule (average rank for ties), and FilterRHEF's three conventions
 * (ranks over n - 1, only values above 0 ranked, annuli under 5 pixels left alone); see that
 * folder's README. Both FilterRHEF paths are held to the manifest's tolerance: filter() on floats
 * and filterHalf() on the half floats ImageFilter stores.
 *
 * <p>The upsilon-split case pins a difference instead of an agreement. sunkit-image splits its
 * two-sided gamma at each annulus's nanmean; the display shader splits at 0.5
 * (resources/glsl/imageCommon.frag, the upsilon block), applied here to the rank as the display
 * does with levels at offset 0 and scale 1. That difference is an open item
 * (projects/jhelioviewer.md:599-605). The case fails when the two start to agree or the shader's
 * split line changes, so whoever closes the item updates this vector in the same change.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.image.RhefConformanceCheck
 */
public final class RhefConformanceCheck {

    private static final Path DIR = Path.of("extra/test/data/rhef-conformance");
    private static final Path SHADER = Path.of("resources/glsl/imageCommon.frag");
    private static final String GLSL_SPLIT = "v = v < .5 ? .5 * pow(2. * v, display.upsilon.x) : 1. - .5 * pow(2. - 2. * v, display.upsilon.y);";

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        Path manifest = DIR.resolve("manifest.json");
        expect("the RHEF vectors are present: " + manifest, Files.isRegularFile(manifest));
        if (Files.isRegularFile(manifest))
            vectors(new JSONObject(Files.readString(manifest, StandardCharsets.UTF_8)));

        // RH-6 adds its golden-bundle pass here, in this class (rhef/00-overview.md, RH-6).

        if (failures != 0) {
            System.out.println(failures + " RHEF conformance failure(s)");
            System.exit(1);
        }
    }

    private static void vectors(JSONObject manifest) throws IOException {
        double tolerance = manifest.getDouble("tolerance");
        System.out.println("  vectors from sunkit-image " + manifest.optString("sunkit_image_version", "(version not recorded)"));
        JSONArray cases = manifest.getJSONArray("cases");
        for (int c = 0; c < cases.length(); c++) {
            JSONObject k = cases.getJSONObject(c);
            String name = k.getString("name");
            int w = k.getInt("width"), h = k.getInt("height");
            float[] input = read(DIR.resolve(k.getString("input")), w * h);
            float[] expected = read(DIR.resolve(k.getString("expected")), w * h);
            float[] viaFloat = new FilterRHEF(null).filter(input.clone(), w, h);
            short[] halves = new short[input.length];
            for (int i = 0; i < input.length; i++)
                halves[i] = Float.floatToFloat16(input[i]);
            float[] viaHalf = new FilterRHEF(null).filterHalf(halves, w, h);

            if ("upsilon-split".equals(name)) {
                JSONArray u = k.getJSONObject("params").getJSONArray("upsilon");
                float[] shown = new float[viaFloat.length];
                for (int i = 0; i < shown.length; i++)
                    shown[i] = upsilon(viaFloat[i], u.getDouble(0), u.getDouble(1));
                double d = maxDiff(shown, expected);
                expect(String.format("%s: the display upsilon (split at 0.5) still differs from sunkit-image's (split at the annulus nanmean): max |d| %.3e > %.0e, as documented",
                        name, d, tolerance), d > tolerance);
                expect(name + ": imageCommon.frag still splits upsilon at 0.5",
                        Files.isRegularFile(SHADER) && Files.readString(SHADER, StandardCharsets.UTF_8).contains(GLSL_SPLIT));
                continue;
            }

            double df = maxDiff(viaFloat, expected), dh = maxDiff(viaHalf, expected);
            expect(String.format("%s: filter() within %.0e of the vector (max |d| %.3e)", name, tolerance, df), df <= tolerance);
            expect(String.format("%s: filterHalf() within %.0e of the vector (max |d| %.3e)", name, tolerance, dh), dh <= tolerance);
            int changed = 0;
            for (int i = 0; i < input.length; i++)
                if (Float.compare(viaFloat[i], input[i]) != 0)
                    changed++;
            expect(name + ": the filter ranked most of the frame rather than passing it through (" + changed + " of " + input.length + ")",
                    changed > input.length / 2);
        }
    }

    /** The upsilon block of imageCommon.frag, transcribed: the curve on [0, 1], any excess carried through. */
    private static float upsilon(float value, double low, double high) {
        double over = Math.max(value - 1, 0), under = Math.min(value, 0);
        double v = Math.clamp(value, 0, 1);
        v = v < .5 ? .5 * Math.pow(2 * v, low) : 1 - .5 * Math.pow(2 - 2 * v, high);
        return (float) (v + over + under);
    }

    /** Largest |a - b|; NaN when exactly one of a pair is NaN (a disagreement), nothing when both are. */
    private static double maxDiff(float[] a, float[] b) {
        double max = 0;
        for (int i = 0; i < a.length; i++) {
            boolean na = Float.isNaN(a[i]), nb = Float.isNaN(b[i]);
            if (na != nb)
                return Double.NaN;
            if (!na)
                max = Math.max(max, Math.abs(a[i] - b[i]));
        }
        return max;
    }

    private static float[] read(Path file, int count) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        if (b.remaining() != 4L * count)
            throw new IOException(file + ": " + b.remaining() + " bytes, expected " + 4L * count);
        float[] out = new float[count];
        b.asFloatBuffer().get(out);
        return out;
    }

}
