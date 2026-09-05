package org.helioviewer.jhv.image;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.helioviewer.jhv.metadata.Region;
import org.helioviewer.jhv.thread.ParallelRange;

/**
 * RHEF's histogram form against the sort it replaces, on a real PUNCH frame.
 *
 * <p>Not a check: it asserts nothing and is not in the suite. It exists so two claims can be
 * tested rather than believed. First, that ranking by a histogram over half-float bit patterns is
 * not an approximation of the sort but the same answer: ImageFilter hands this filter values that
 * were half floats a moment before, so there are at most 65536 of them and a bin per pattern
 * enumerates the values rather than quantising them. Second, what the reformulation is worth.
 *
 * <p>The input is a real frame, PUNCH_L3_CAM_20250923130400_v0l.fits, 4096 x 4096, dumped to raw
 * float32 by astropy and normalised here the way the decoder normalises before storing half
 * floats. Synthetic Gaussian noise has a different number of distinct values per annulus, which is
 * the quantity this whole comparison turns on, so it is not a substitute.
 *
 * <p>Run: java -Xmx8g -cp bin:extra/test-classes org.helioviewer.jhv.image.RhefProfile &lt;frame.f32&gt;
 */
public final class RhefProfile {

    private static final int MIN_BIN_COUNT = 5;

    public static void main(String[] args) throws IOException {
        int w = 4096, h = 4096;
        float[] raw = readRaw(Path.of(args[0]), w * h);

        // 1 R_sun = RSUN_ARC / 3600 / CDELT1 = 955.9741 / 3600 / 0.0225 = 11.802 px, so the frame
        // half-width is 2048 / 11.802 = 173.5 R_sun. Sun-centred: CRPIX1 = CRPIX2 = 2048.
        double halfWidth = 2048 / (955.9741092736674 / 3600 / 0.0225);
        Region region = new Region(-halfWidth, -halfWidth, 2 * halfWidth, 2 * halfWidth);

        System.out.printf("real PUNCH L3 CAM frame (PUNCH_L3_CAM_20250923130400_v0l), %d x %d, %d cores%n",
                w, h, Runtime.getRuntime().availableProcessors());
        System.out.printf("%n  The histogram's advantage depends on how many distinct values a frame holds,%n");
        System.out.printf("  so that is swept rather than assumed. The application's own readout measured%n");
        System.out.printf("  7315 levels on this layer, which is why the sweep brackets it.%n%n");
        System.out.printf("  %8s  %9s  %9s  %7s  %s%n", "levels", "sort ms", "hist ms", "ratio", "pixels differing");

        float[] best = null;
        for (int levels : new int[]{256, 1024, 4096, 7315, 16384, 65536}) {
            float[] data = quantise(raw, levels);
            int distinct = countDistinct(data);
            FilterRHEF filter = new FilterRHEF(new SunCenteredRegion(region));
            filter.filter(data, w, h);
            sortRank(data, w, h, region);

            double histMs = Double.MAX_VALUE, sortMs = Double.MAX_VALUE;
            float[] hist = null, sorted = null;
            for (int rep = 0; rep < 3; rep++) { // best of three: the floor, not the GC
                long t = System.nanoTime();
                hist = filter.filter(data, w, h);
                histMs = Math.min(histMs, (System.nanoTime() - t) / 1e6);
                t = System.nanoTime();
                sorted = sortRank(data, w, h, region);
                sortMs = Math.min(sortMs, (System.nanoTime() - t) / 1e6);
            }
            int differing = 0;
            for (int i = 0; i < data.length; i++)
                if (sorted[i] != hist[i])
                    differing++;
            System.out.printf("  %8d  %9.1f  %9.1f  %6.2fx  %d of %d%n",
                    distinct, sortMs, histMs, sortMs / histMs, differing, data.length);
            if (levels == 7315) {
                best = hist;
                writeFloats(Path.of(args[0]).resolveSibling("punch_normalised.f32"), data);
                writeFloats(Path.of(args[0]).resolveSibling("punch_rhef_java.f32"), hist);
            }
        }
        // A linear map into [0, 1] cannot produce more than about 2050 distinct half codes: near
        // 1.0 the half spacing is ~1e-3. The application's readout measured 7315 levels on this
        // layer, which its stretch reaches by pushing data toward zero where half float is dense.
        // The ratio has to be bounded from that side too, or the claim only covers half the range.
        for (int codes : new int[]{8000, 30000}) {
            float[] data = spreadOverCodes(raw, codes);
            int distinct = countDistinct(data);
            FilterRHEF filter = new FilterRHEF(new SunCenteredRegion(region));
            filter.filter(data, w, h);
            sortRank(data, w, h, region);
            double histMs = Double.MAX_VALUE, sortMs = Double.MAX_VALUE;
            float[] hist = null, sorted = null;
            for (int rep = 0; rep < 3; rep++) {
                long t = System.nanoTime();
                hist = filter.filter(data, w, h);
                histMs = Math.min(histMs, (System.nanoTime() - t) / 1e6);
                t = System.nanoTime();
                sorted = sortRank(data, w, h, region);
                sortMs = Math.min(sortMs, (System.nanoTime() - t) / 1e6);
            }
            int differing = 0;
            for (int i = 0; i < data.length; i++)
                if (sorted[i] != hist[i])
                    differing++;
            System.out.printf("  %8d  %9.1f  %9.1f  %6.2fx  %d of %d   (rank order preserved, codes spread)%n",
                    distinct, sortMs, histMs, sortMs / histMs, differing, data.length);
        }

        System.out.printf("%n  wrote punch_normalised.f32 and punch_rhef_java.f32 (the 7315-level case) beside the input%n");
        if (best == null)
            throw new AssertionError();
    }

    private static void writeFloats(Path path, float[] values) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(4 * values.length).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : values)
            bb.putFloat(v);
        Files.write(path, bb.array());
    }

    /**
     * The same frame, its values mapped monotonically onto a wide span of half-float codes.
     *
     * <p>The pixel ORDER is untouched, so every rank RHEF produces is the rank it would produce on
     * the original: what changes is only how many distinct codes the annuli contain, which is the
     * variable being swept. This is the one part of the sweep that is not a possible output of the
     * decoder, and it is here to bound the answer at high diversity rather than to imitate a
     * particular stretch.
     */
    private static float[] spreadOverCodes(float[] raw, int codes) {
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : raw)
            if (Float.isFinite(v)) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        float scale = 1f / (max - min);
        float[] out = new float[raw.length];
        // Half codes 0x0400 upward are the normal numbers; walking a contiguous run of them gives
        // monotonically increasing values with a distinct code per step.
        for (int i = 0; i < raw.length; i++) {
            if (!Float.isFinite(raw[i])) {
                out[i] = 0;
                continue;
            }
            double t = (raw[i] - min) * scale;
            int code = 0x0400 + (int) Math.round(t * (codes - 1));
            out[i] = Float.float16ToFloat((short) code);
        }
        return out;
    }

    private static int countDistinct(float[] data) {
        boolean[] seen = new boolean[1 << 16];
        int distinct = 0;
        for (float v : data) {
            int bits = Float.floatToFloat16(v) & 0xFFFF;
            if (v > 0 && !seen[bits]) {
                seen[bits] = true;
                distinct++;
            }
        }
        return distinct;
    }

    /**
     * The frame normalised to [0, 1] and quantised to a chosen number of levels, then put through
     * half float, which is the storage the pipeline uses. Quantising is how the number of distinct
     * values is controlled; the decoder gets its own count from whatever stretch is in force, and
     * the point of the sweep is that the answer depends on that count rather than on this file.
     *
     * <p>Negative values (this is a background-subtracted product) stay at or below zero and are
     * excluded by RHEF's own v &gt; 0 test, exactly as in the application.
     */
    private static float[] quantise(float[] raw, int levels) {
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : raw)
            if (Float.isFinite(v)) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        float scale = 1f / (max - min);
        float[] out = new float[raw.length];
        for (int i = 0; i < raw.length; i++) {
            if (!Float.isFinite(raw[i])) {
                out[i] = 0;
                continue;
            }
            double t = (raw[i] - min) * scale;
            double q = Math.round(t * (levels - 1)) / (double) (levels - 1);
            out[i] = Float.float16ToFloat(Float.floatToFloat16((float) q));
        }
        return out;
    }

    /** The file as astropy wrote it: raw float32, no normalisation. */
    private static float[] readRaw(Path path, int expected) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length != 4 * expected)
            throw new IOException("expected " + expected + " float32, got " + bytes.length / 4);
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[expected];
        for (int i = 0; i < expected; i++)
            out[i] = bb.getFloat();
        return out;
    }

    /** The shipped algorithm as of 0f7f735ac: a comparison sort of (value, index) per annulus. */
    private static float[] sortRank(float[] data, int width, int height, Region region) {
        double pixX = region.width / width, pixY = region.height / height;
        double llx = region.llx, lly = region.lly;
        double invBinWidth = 1 / Math.min(pixX, pixY);
        double dxMax = Math.max(Math.abs(llx), Math.abs(llx + width * pixX));
        double dyMax = Math.max(Math.abs(lly), Math.abs(lly + height * pixY));
        int numBins = (int) (Math.sqrt(dxMax * dxMax + dyMax * dyMax) * invBinWidth) + 1;

        int length = width * height;
        int[] binOf = new int[length];
        double[] dx2 = new double[width];
        for (int x = 0; x < width; x++) {
            double dx = llx + (x + .5) * pixX;
            dx2[x] = dx * dx;
        }
        ParallelRange.run(height, (from, to) -> {
            for (int y = from; y < to; y++) {
                double dy = lly + (y + .5) * pixY;
                double dy2 = dy * dy;
                int rowBase = y * width;
                for (int x = 0; x < width; x++)
                    binOf[rowBase + x] = (int) (Math.sqrt(dx2[x] + dy2) * invBinWidth);
            }
        });

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
        ParallelRange.run(numBins, (from, to) -> {
            long[] packed = new long[0];
            for (int b = from; b < to; b++) {
                int lo = offset[b], hi = offset[b + 1];
                if (hi - lo < MIN_BIN_COUNT)
                    continue;
                if (packed.length < hi - lo)
                    packed = new long[hi - lo];
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
        });
        return out;
    }

}
