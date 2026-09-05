package org.helioviewer.jhv.image;

import java.util.Arrays;

import org.helioviewer.jhv.metadata.Region;

/**
 * RHEF ranks by histogram rather than by sorting, and the two must agree exactly.
 *
 * <p>The reformulation is only sound because of a property of the input: ImageFilter converts the
 * stored half floats to float before calling the filter, so every value is one of at most 65536,
 * and a bin per bit pattern enumerates the possible values instead of quantising them. If that
 * ever stops being true, if a filter is handed float32 straight from a FITS file, say, the
 * histogram silently becomes an approximation and this check is what says so.
 *
 * <p>The reference here is the algorithm RHEF used until 2026-09-05: a comparison sort of packed
 * (value, index) pairs per annulus, with average rank for ties. It is kept as an independent
 * statement of the answer, not as an implementation.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.image.RhefRankCheck
 */
public final class RhefRankCheck {

    private static final int MIN_BIN_COUNT = 5;

    private static int failures;

    public static void main(String[] args) {
        // Sizes that are not powers of two, and an odd region offset, so an off-by-one in the
        // annulus binning cannot hide behind symmetry.
        for (int[] wh : new int[][]{{97, 61}, {128, 128}, {201, 199}}) {
            int w = wh[0], h = wh[1];
            for (double ties : new double[]{1, 8, 64, 1024}) {
                float[] data = frame(w, h, ties);
                Region region = new Region(-w / 2. - 0.3, -h / 2. + 0.7, w, h);
                float[] hist = new FilterRHEF(new SunCenteredRegion(region)).filter(data.clone(), w, h);
                float[] sorted = sortRank(data, w, h, region);
                int differing = 0;
                for (int i = 0; i < data.length; i++)
                    if (sorted[i] != hist[i])
                        differing++;
                expect(differing == 0, String.format("%dx%d with ~%.0f distinct levels: %d pixels differ", w, h, ties, differing));
            }
        }

        // Guard against passing for the wrong reason. Every comparison above is "these two agree",
        // which a filter that returned its input unchanged would satisfy perfectly. So insist that
        // the filter actually did something: on a 201x199 frame most pixels must come back changed.
        int w = 201, h = 199;
        float[] data = frame(w, h, 1024);
        Region region = new Region(-w / 2. - 0.3, -h / 2. + 0.7, w, h);
        float[] ranked = new FilterRHEF(new SunCenteredRegion(region)).filter(data.clone(), w, h);
        int changed = 0;
        for (int i = 0; i < data.length; i++)
            if (ranked[i] != data[i])
                changed++;
        expect(changed > data.length / 2,
                "the filter ranks most of the frame rather than passing it through: " + changed + " of " + data.length);

        if (failures != 0)
            throw new AssertionError(failures + " RHEF rank failure(s)");
        System.out.println("RhefRankCheck: PASS");
    }

    /**
     * A frame whose values are half-float representable, with a controllable number of distinct
     * levels: few levels means many ties, which is where a rank rule is easiest to get wrong.
     */
    private static float[] frame(int w, int h, double levels) {
        float[] data = new float[w * h];
        java.util.Random rnd = new java.util.Random(11);
        for (int i = 0; i < data.length; i++) {
            double t = Math.abs(rnd.nextGaussian()) / 6;
            double q = Math.round(Math.min(1, t) * (levels - 1)) / levels;
            data[i] = Float.float16ToFloat(Float.floatToFloat16((float) q));
        }
        return data;
    }

    /** The algorithm RHEF used before the histogram: sort packed (value, index) pairs per annulus. */
    private static float[] sortRank(float[] data, int width, int height, Region region) {
        double pixX = region.width / width, pixY = region.height / height;
        double llx = region.llx, lly = region.lly;
        double invBinWidth = 1 / Math.min(pixX, pixY);
        double dxMax = Math.max(Math.abs(llx), Math.abs(llx + width * pixX));
        double dyMax = Math.max(Math.abs(lly), Math.abs(lly + height * pixY));
        int numBins = (int) (Math.sqrt(dxMax * dxMax + dyMax * dyMax) * invBinWidth) + 1;

        int length = width * height;
        int[] binOf = new int[length];
        for (int y = 0; y < height; y++) {
            double dy = lly + (y + .5) * pixY;
            for (int x = 0; x < width; x++) {
                double dx = llx + (x + .5) * pixX;
                binOf[y * width + x] = (int) (Math.sqrt(dx * dx + dy * dy) * invBinWidth);
            }
        }
        int[] offset = new int[numBins + 1];
        for (int i = 0; i < length; i++)
            offset[binOf[i] + 1]++;
        for (int b = 0; b < numBins; b++)
            offset[b + 1] += offset[b];
        int[] order = new int[length];
        int[] cursor = Arrays.copyOf(offset, numBins);
        for (int i = 0; i < length; i++)
            order[cursor[binOf[i]]++] = i;

        float[] out = data.clone();
        for (int b = 0; b < numBins; b++) {
            int lo = offset[b], hi = offset[b + 1];
            if (hi - lo < MIN_BIN_COUNT)
                continue;
            long[] packed = new long[hi - lo];
            int n = 0;
            for (int j = lo; j < hi; j++) {
                int idx = order[j];
                float v = data[idx];
                if (v > 0)
                    packed[n++] = (long) Float.floatToRawIntBits(v) << 32 | idx;
            }
            if (n < MIN_BIN_COUNT)
                continue;
            Arrays.sort(packed, 0, n);
            float invRange = 1f / (n - 1);
            int i = 0;
            while (i < n) {
                long bits = packed[i] >>> 32;
                int j = i;
                while (j + 1 < n && packed[j + 1] >>> 32 == bits)
                    j++;
                float value = .5f * (i + j) * invRange;
                for (int k = i; k <= j; k++)
                    out[(int) packed[k]] = value;
                i = j + 1;
            }
        }
        return out;
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

}
