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
 * <p>The upsilon-split case pins a difference, and pins its two components separately. The vector is
 * sunkit-image's own output: ranks R / n, then apply_upsilon. The display shows FilterRHEF's ranks,
 * (R - 1) / (n - 1), through the shader's two-sided gamma split at 0.5 (resources/glsl/imageCommon.frag,
 * the upsilon block, applied here to the rank as the display does with levels at offset 0 and scale 1).
 * Measured on this vector (ranked pixels; the numbers are in README.md next to it):
 * <ul>
 * <li>Rank normalisation is almost all of the difference. On FilterRHEF's own ranks the display curve
 * differs from the vector by max |d| 0.3078, because the 0.35 power is steepest near 0, where
 * (R - 1) / (n - 1) = 0 for the lowest pixel and R / n = 1 / n for sunkit-image. This check requires that
 * number to stay in [0.30, 0.32]: it moves, and the check fails, when FilterRHEF changes its
 * normalisation, or when the curve changes.
 * <li>Converting FilterRHEF's ranks to R / n first (R = r (n - 1) + 1) and keeping the shader's split
 * gives max |d| about 0.002: the split at 0.5 against sunkit-image's nanmean is not the cause, since a
 * nanmean of R / n ranks is (n + 1) / 2n and sunkit-image's curve is continuous there. That residual is
 * float32 rounding of the top rank (0.99999994 for 1), amplified by the 0.35 power. This check requires it
 * below 0.003: it fails when the split or the curve moves away from sunkit-image's.
 * </ul>
 * The shader's split line is also pinned as text, so whoever changes it updates this vector in the
 * same change. The open item is projects/jhelioviewer.md:652-658 ("Consider the Upsilon split-point
 * discrepancy"); its premise, that the split makes the output differ, does not hold on rank data.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.image.RhefConformanceCheck
 */
public final class RhefConformanceCheck {

    private static final Path DIR = Path.of("extra/test/data/rhef-conformance");
    private static final Path SHADER = Path.of("resources/glsl/imageCommon.frag");
    private static final String GLSL_SPLIT = "v = v < .5 ? .5 * pow(2. * v, display.upsilon.x) : 1. - .5 * pow(2. - 2. * v, display.upsilon.y);";

    // Measured on the upsilon-split vector with FilterRHEF, JDK 21: 0.3078 on its own ranks, about 0.002 on R / n.
    private static final double OWN_MIN = 0.30, OWN_MAX = 0.32, CONVERTED_MAX = 0.003;

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
        JSONObject cross = manifest.optJSONObject("crosscheck");
        System.out.println("  vectors: scipy rankdata with FilterRHEF's conventions; sunkit-image "
                + manifest.optString("sunkit_image_version", "(version not recorded)")
                + " is the cross-check on radial-falloff (" + (cross == null ? "not recorded" : cross.optInt("pixels") + " pixels, max |d| " + cross.optDouble("max_abs_diff"))
                + ") and the source of the upsilon-split vector");
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
                upsilonSplit(name, k.getJSONObject("params").getJSONArray("upsilon"), input, expected, viaFloat, w, h);
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

    /** The two components of the upsilon-split difference, each held to its own bound. */
    private static void upsilonSplit(String name, JSONArray u, float[] input, float[] expected, float[] ranks, int w, int h) throws IOException {
        String shader = Files.isRegularFile(SHADER) ? Files.readString(SHADER, StandardCharsets.UTF_8) : "";
        expect(name + ": imageCommon.frag still splits upsilon at 0.5", shader.contains(GLSL_SPLIT));

        // The pixels sunkit-image ranks: finite and above 0, in an annulus with at least 5 of them.
        int[] ring = new int[w * h];
        int bins = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                bins = Math.max(bins, ring[y * w + x] = (int) Math.floor(Math.hypot(x + .5 - w / 2.0, y + .5 - h / 2.0)) + 1);
        int[] count = new int[bins + 1];
        for (int i = 0; i < input.length; i++)
            if (input[i] > 0 && Float.isFinite(input[i]))
                count[ring[i]]++;
        boolean[] ranked = new boolean[input.length];
        float[] sunkitRanks = new float[input.length];
        int n = 0;
        for (int i = 0; i < input.length; i++)
            if (input[i] > 0 && Float.isFinite(input[i]) && count[ring[i]] >= 5) {
                ranked[i] = true;
                n++;
                // FilterRHEF's (R - 1) / (n - 1) back to R, then to sunkit-image's R / n.
                sunkitRanks[i] = (float) ((ranks[i] * (count[ring[i]] - 1.0) + 1) / count[ring[i]]);
            }

        float[] own = new float[input.length], converted = new float[input.length];
        for (int i = 0; i < input.length; i++) {
            own[i] = upsilon(ranks[i], u.getDouble(0), u.getDouble(1));
            converted[i] = upsilon(sunkitRanks[i], u.getDouble(0), u.getDouble(1));
        }
        double dOwn = maxDiff(own, expected, ranked), dConverted = maxDiff(converted, expected, ranked);
        expect(String.format("%s: %d ranked pixels, FilterRHEF's own ranks through the display curve differ from sunkit-image's by max |d| %.4f, in [%.2f, %.2f] (rank normalisation (R - 1) / (n - 1) against R / n)",
                name, n, dOwn, OWN_MIN, OWN_MAX), dOwn >= OWN_MIN && dOwn <= OWN_MAX);
        expect(String.format("%s: after converting the ranks to R / n the display curve is within %.3f of sunkit-image's (max |d| %.2e; the split and the curve)",
                name, CONVERTED_MAX, dConverted), dConverted <= CONVERTED_MAX);
    }

    /** The upsilon block of imageCommon.frag, transcribed: the curve on [0, 1], any excess carried through. */
    private static float upsilon(float value, double low, double high) {
        double over = Math.max(value - 1, 0), under = Math.min(value, 0);
        double v = Math.clamp(value, 0, 1);
        v = v < .5 ? .5 * Math.pow(2 * v, low) : 1 - .5 * Math.pow(2 - 2 * v, high);
        return (float) (v + over + under);
    }

    /** Largest |a - b| over the flagged pixels only. */
    private static double maxDiff(float[] a, float[] b, boolean[] only) {
        double max = 0;
        for (int i = 0; i < a.length; i++)
            if (only[i])
                max = Math.max(max, Math.abs(a[i] - b[i]));
        return max;
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
