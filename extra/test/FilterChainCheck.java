package org.helioviewer.jhv.image;

import java.util.Arrays;

import org.helioviewer.jhv.metadata.Region;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Chained filters: the model, the arithmetic and the session form.
 *
 * <p>The arithmetic is checked against the filters called by hand, with the same float operations:
 * stage i gives y = (1 - w) * y_prev + w * F(y_prev), a chain of one filter is the old single filter
 * bit for bit, w = 0 passes the input through and w = 1 is the nested call.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.image.FilterChainCheck
 */
public final class FilterChainCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok)
            failures++;
    }

    private static final int W = 160, H = 144;

    private static short[] frame() {
        short[] halves = new short[W * H];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                double r = Math.hypot(x - W / 2., y - H / 2.);
                double v = 0.9 * Math.exp(-r / 40) + 0.08 * Math.sin(x * 0.7) * Math.cos(y * 0.45) + 0.02 * ((x * 31 + y * 17) % 13) / 13.;
                halves[y * W + x] = Float.floatToFloat16((float) Math.clamp(v, 0, 1));
            }
        return halves;
    }

    private static SunCenteredRegion region() {
        return new SunCenteredRegion(new Region(-W / 2. - 0.3, -H / 2. + 0.7, W, H));
    }

    private static float[] floats(short[] halves) {
        float[] f = new float[halves.length];
        for (int i = 0; i < f.length; i++)
            f[i] = Float.float16ToFloat(halves[i]);
        return f;
    }

    private static boolean same(float[] a, float[] b) {
        return Arrays.equals(a, b);
    }

    private static float[] run(ImageFilter.Type first, FilterChain chain, short[] halves) {
        return ImageFilter.of(first, chain, region()).apply(halves, W, H);
    }

    public static void main(String[] args) {
        // ---- the model
        FilterChain two = FilterChain.EMPTY.add(new FilterChain.Stage(ImageFilter.Type.MGN, 0.6f));
        expect("a chain with a stage is not empty and keeps it", !two.isEmpty() && two.size() == 1 && two.stages().get(0).strength() == 0.6f);
        expect("the default cap is 3 filters in all, so 2 after the first", FilterChain.maxStages() == 3 && FilterChain.maxExtraStages() == 2);
        FilterChain full = two.add(new FilterChain.Stage(ImageFilter.Type.WOW, 1));
        boolean refused = false;
        try {
            full.add(new FilterChain.Stage(ImageFilter.Type.RHEF, 1));
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        expect("a third stage after the first is refused at the cap", refused);
        expect("equal chains are equal values (they sit in cache keys)",
                two.equals(FilterChain.EMPTY.add(new FilterChain.Stage(ImageFilter.Type.MGN, 0.6f))) && two.hashCode() == FilterChain.EMPTY.add(new FilterChain.Stage(ImageFilter.Type.MGN, 0.6f)).hashCode());
        expect("a different strength is a different key", !two.equals(two.with(0, new FilterChain.Stage(ImageFilter.Type.MGN, 0.5f))));
        for (float bad : new float[]{-0.1f, 1.1f, Float.NaN}) {
            boolean threw = false;
            try {
                new FilterChain.Stage(ImageFilter.Type.MGN, bad);
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            expect("strength " + bad + " is refused", threw);
        }
        expect("a stage of None is refused", throwsOnNone());

        // ---- the rank fraction the display uses
        expect("RHEF alone is a full rank", FilterChain.EMPTY.rankFraction(ImageFilter.Type.RHEF) == 1);
        expect("MGN alone has no rank", FilterChain.EMPTY.rankFraction(ImageFilter.Type.MGN) == 0);
        expect("no filter has no rank", FilterChain.EMPTY.rankFraction(ImageFilter.Type.None) == 0);
        expect("MGN on RHEF is still a full rank", two.rankFraction(ImageFilter.Type.RHEF) == 1);
        FilterChain rhefOn = FilterChain.EMPTY.add(new FilterChain.Stage(ImageFilter.Type.RHEF, 0.6f));
        expect("RHEF at 60% on MGN is 0.6 rank", Math.abs(rhefOn.rankFraction(ImageFilter.Type.MGN) - 0.6) < 1e-6);
        FilterChain rhefTwice = FilterChain.EMPTY.add(new FilterChain.Stage(ImageFilter.Type.RHEF, 0.5f)).add(new FilterChain.Stage(ImageFilter.Type.RHEF, 0.5f));
        expect("two RHEF at 50% on MGN is 0.75 rank", Math.abs(rhefTwice.rankFraction(ImageFilter.Type.MGN) - 0.75) < 1e-6);
        expect("usesRhef follows the rank", rhefOn.usesRhef(ImageFilter.Type.MGN) && !two.usesRhef(ImageFilter.Type.MGN) && two.usesRhef(ImageFilter.Type.RHEF));
        expect("a zero-strength RHEF stage is no rank", !FilterChain.EMPTY.add(new FilterChain.Stage(ImageFilter.Type.RHEF, 0)).usesRhef(ImageFilter.Type.MGN));
        expect("the display constants at rank 1 are the old RHEF ones, at rank 0 the old plain ones",
                org.helioviewer.jhv.opengl.GLSLImage.responseScale(1, 3.7) == 1 && org.helioviewer.jhv.opengl.GLSLImage.responseScale(0, 3.7) == 3.7
                        && org.helioviewer.jhv.opengl.GLSLImage.upsilonScale(1, 0.4) == 0.4 && org.helioviewer.jhv.opengl.GLSLImage.upsilonScale(0, 0.4) == 1);

        // ---- the session form
        JSONArray json = full.toJson();
        expect("a chain survives a JSON round trip", FilterChain.fromJson(json).equals(full));
        expect("no chain in a session is the empty chain", FilterChain.fromJson(null).isEmpty());
        JSONArray odd = new JSONArray()
                .put(new JSONObject().put("type", "NoSuchFilter").put("strength", 0.5))
                .put(new JSONObject().put("type", "None").put("strength", 0.5))
                .put(new JSONObject().put("type", "MGN").put("strength", 7))
                .put("junk")
                .put(new JSONObject().put("type", "WOW").put("strength", 0.25));
        FilterChain read = FilterChain.fromJson(odd);
        expect("a session read skips what it cannot use and keeps the rest: " + read, read.size() == 1 && read.stages().get(0).type() == ImageFilter.Type.WOW);
        JSONArray many = new JSONArray();
        for (int i = 0; i < 9; i++)
            many.put(new JSONObject().put("type", "MGN").put("strength", 0.5));
        expect("a session with too many stages is cut at the cap", FilterChain.fromJson(many).size() == FilterChain.maxExtraStages());

        // ---- ImageProcessingSettings
        int[] changes = {0};
        ImageProcessingSettings s = new ImageProcessingSettings(() -> changes[0]++);
        s.setFilter(ImageFilter.Type.RHEF, two);
        expect("one change for filter and chain together", changes[0] == 1 && s.getFilter() == ImageFilter.Type.RHEF && s.getFilterChain().equals(two));
        s.setFilter(ImageFilter.Type.RHEF, two);
        expect("the same again changes nothing", changes[0] == 1);
        s.setFilter(ImageFilter.Type.MGN);
        expect("changing only the first filter keeps the chain", s.getFilterChain().equals(two) && changes[0] == 2);
        s.setFilter(ImageFilter.Type.None);
        expect("None drops the whole chain", s.getFilterChain().isEmpty() && changes[0] == 3);
        s.setFilter(ImageFilter.Type.None, two);
        expect("a chain with no first filter is dropped", s.getFilterChain().isEmpty() && changes[0] == 3);

        // ---- the arithmetic
        short[] halves = frame();
        float[] x = floats(halves);
        for (ImageFilter.Type first : new ImageFilter.Type[]{ImageFilter.Type.RHEF, ImageFilter.Type.MGN, ImageFilter.Type.WOW})
            expect("an empty chain is the single " + first + " bit for bit",
                    same(run(first, FilterChain.EMPTY, halves), ImageFilter.of(first, FilterChain.EMPTY, region()).apply(halves, W, H))
                            && same(run(first, FilterChain.EMPTY, halves), single(first, halves)));

        float[] rhef = single(ImageFilter.Type.RHEF, halves);
        float[] mgnOnRhef = new FilterMGN().filter(rhef.clone(), W, H);
        for (float w : new float[]{0.6f, 0.25f, 1f}) {
            float[] expected = new float[x.length];
            float a = 1 - w;
            for (int i = 0; i < x.length; i++)
                expected[i] = w == 1 ? mgnOnRhef[i] : a * rhef[i] + w * mgnOnRhef[i];
            expect("MGN at " + Math.round(w * 100) + "% on RHEF is (1 - w) * RHEF + w * MGN(RHEF), exactly",
                    same(expected, run(ImageFilter.Type.RHEF, FilterChain.EMPTY.add(new FilterChain.Stage(ImageFilter.Type.MGN, w)), halves)));
        }
        expect("weight 0 passes the previous result through",
                same(rhef, run(ImageFilter.Type.RHEF, FilterChain.EMPTY.add(new FilterChain.Stage(ImageFilter.Type.MGN, 0)), halves)));
        expect("weight 1 is the nested call",
                same(mgnOnRhef, run(ImageFilter.Type.RHEF, FilterChain.EMPTY.add(new FilterChain.Stage(ImageFilter.Type.MGN, 1)), halves)));

        // the other order: RHEF on top of MGN (RHEF takes the MGN output as half floats by default)
        float[] mgn = single(ImageFilter.Type.MGN, halves);
        short[] mgnHalves = new short[mgn.length];
        for (int i = 0; i < mgn.length; i++)
            mgnHalves[i] = Float.floatToFloat16(mgn[i]);
        float[] rhefOnMgn = new FilterRHEF(region()).filterHalf(mgnHalves, W, H);
        float[] expectedSwap = new float[x.length];
        float aSwap = 1 - 0.6f; // 1 - 0.6f is not 0.4f in floats; the code uses (1 - w)
        for (int i = 0; i < x.length; i++)
            expectedSwap[i] = aSwap * mgn[i] + 0.6f * rhefOnMgn[i];
        expect("RHEF at 60% on MGN uses the MGN output as the input and keeps it for the mix",
                same(expectedSwap, run(ImageFilter.Type.MGN, FilterChain.EMPTY.add(new FilterChain.Stage(ImageFilter.Type.RHEF, 0.6f)), halves)));

        // the filters are not commutative; the swap button is not a no-op
        expect("MGN on RHEF differs from RHEF on MGN at 60%", !same(
                run(ImageFilter.Type.RHEF, FilterChain.EMPTY.add(new FilterChain.Stage(ImageFilter.Type.MGN, 0.6f)), halves),
                run(ImageFilter.Type.MGN, FilterChain.EMPTY.add(new FilterChain.Stage(ImageFilter.Type.RHEF, 0.6f)), halves)));

        // the input is not changed by running a chain (a caller may keep it)
        short[] before = halves.clone();
        run(ImageFilter.Type.RHEF, full, halves);
        expect("running a chain leaves the input alone", Arrays.equals(before, halves));

        // three filters: the cap's longest chain
        FilterChain three = FilterChain.EMPTY.add(new FilterChain.Stage(ImageFilter.Type.MGN, 0.5f)).add(new FilterChain.Stage(ImageFilter.Type.WOW, 0.5f));
        float[] y = rhef;
        y = mixed(y, new FilterMGN().filter(y.clone(), W, H), 0.5f);
        y = mixed(y, new FilterWOW().filter(y.clone(), W, H), 0.5f);
        expect("three filters in series apply in order", same(y, run(ImageFilter.Type.RHEF, three, halves)));

        // description for people
        expect("describe names the steps: " + FilterChain.describe(ImageFilter.Type.RHEF, two),
                FilterChain.describe(ImageFilter.Type.RHEF, two).equals("RHEF then 60% MGN (40% previous)")
                        && FilterChain.describe(ImageFilter.Type.None, two).equals("No filter")
                        && FilterChain.describe(ImageFilter.Type.MGN, FilterChain.EMPTY).equals("MGN"));

        System.out.println(failures == 0 ? "FilterChainCheck: all ok" : "FilterChainCheck: " + failures + " FAILED");
        if (failures != 0)
            System.exit(1);
    }

    private static boolean throwsOnNone() {
        try {
            new FilterChain.Stage(ImageFilter.Type.None, 1);
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private static float[] mixed(float[] y, float[] f, float w) {
        float a = 1 - w;
        float[] out = new float[y.length];
        for (int i = 0; i < y.length; i++)
            out[i] = a * y[i] + w * f[i];
        return out;
    }

    // The single filter, as the code before chains made it: of(type) on the half floats.
    private static float[] single(ImageFilter.Type type, short[] halves) {
        return switch (type) {
            case RHEF -> new FilterRHEF(region()).filterHalf(halves, W, H);
            case MGN -> new FilterMGN().filterHalf(halves, W, H);
            case WOW -> new FilterWOW().filterHalf(halves, W, H);
            case None -> floats(halves);
        };
    }
}
