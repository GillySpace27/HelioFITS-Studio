package org.helioviewer.jhv.image;

import java.util.Arrays;

import org.helioviewer.jhv.metadata.Region;

/**
 * What RHEF costs, and what the histogram reformulation costs, at PUNCH mosaic size.
 *
 * <p>Not a check: it asserts nothing. It exists so the choice between keeping RHEF on the CPU and
 * moving it to the GPU is made against measurements rather than intuition, and so the claim that a
 * 16-bit histogram reproduces the sort exactly can be tested rather than believed.
 *
 * <p>Run: java -Xmx8g -cp bin:extra/test-classes org.helioviewer.jhv.image.RhefProfile
 */
public final class RhefProfile {

    public static void main(String[] args) {
        int w = 4096, h = 4096;
        Region region = new Region(-32, -32, 64, 64);

        // Half-float values, because that is what RHEF is actually handed: ImageFilter.apply
        // converts the stored shorts to float and back, so every value is one of at most 65536.
        float[] data = new float[w * h];
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < data.length; i++) {
            float v = (float) Math.abs(rnd.nextGaussian()) * 1e-13f;
            data[i] = Float.float16ToFloat(Float.floatToFloat16(v));
        }

        FilterRHEF filter = new FilterRHEF(new SunCenteredRegion(region));

        filter.filter(data, w, h); // warm up the JIT
        long t = System.nanoTime();
        float[] sorted = filter.filter(data, w, h);
        double sortMs = (System.nanoTime() - t) / 1e6;

        t = System.nanoTime();
        float[] hist = histogramRhef(data, w, h, region);
        double histMs = (System.nanoTime() - t) / 1e6;

        // Do the two agree? They should, exactly: a bin per half-float bit pattern is not a
        // quantisation, it is the set of values that exist.
        int differing = 0;
        double worst = 0;
        for (int i = 0; i < data.length; i++) {
            double d = Math.abs(sorted[i] - hist[i]);
            if (d > 1e-6) {
                differing++;
                worst = Math.max(worst, d);
            }
        }

        System.out.printf("RHEF at %d x %d, %d cores%n", w, h, Runtime.getRuntime().availableProcessors());
        System.out.printf("  sort per annulus (as shipped)   %7.1f ms%n", sortMs);
        System.out.printf("  histogram over half-float bits  %7.1f ms%n", histMs);
        System.out.printf("  pixels differing by > 1e-6      %d of %d, worst %.2e%n", differing, data.length, worst);
        System.out.printf("%n  at 245 frames: %.1f s sorted, %.1f s histogram%n", sortMs * 245 / 1000, histMs * 245 / 1000);
    }

    /**
     * The same filter with the sort replaced by a histogram over the 16-bit patterns.
     *
     * <p>Deliberately single-threaded and unoptimised: the question is whether the arithmetic
     * works and roughly what it costs, not how fast this particular transcription can be made.
     */
    private static float[] histogramRhef(float[] data, int width, int height, Region region) {
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
        int[] counts = new int[1 << 16];
        for (int b = 0; b < numBins; b++) {
            int lo = offset[b], hi = offset[b + 1];
            if (hi - lo < 5)
                continue;
            // Histogram this annulus, remembering which bins were touched so the scan below
            // costs the number of distinct values and not 65536.
            int n = 0, distinct = 0;
            int[] touched = new int[hi - lo];
            for (int j = lo; j < hi; j++) {
                float v = data[order[j]];
                if (!(v > 0))
                    continue;
                int bits = Float.floatToFloat16(v) & 0xFFFF;
                if (counts[bits]++ == 0)
                    touched[distinct++] = bits;
                n++;
            }
            if (n < 5) {
                for (int i = 0; i < distinct; i++)
                    counts[touched[i]] = 0;
                continue;
            }
            Arrays.sort(touched, 0, distinct); // over distinct values, not over pixels
            float invRange = 1f / (n - 1);
            int cumulative = 0;
            float[] rankOf = new float[distinct];
            for (int i = 0; i < distinct; i++) {
                int c = counts[touched[i]];
                // Average rank of a run of equal values: the same rule the sort applies.
                rankOf[i] = .5f * (cumulative + cumulative + c - 1) * invRange;
                cumulative += c;
            }
            for (int j = lo; j < hi; j++) {
                int idx = order[j];
                float v = data[idx];
                if (!(v > 0))
                    continue;
                int bits = Float.floatToFloat16(v) & 0xFFFF;
                int at = Arrays.binarySearch(touched, 0, distinct, bits);
                out[idx] = rankOf[at];
            }
            for (int i = 0; i < distinct; i++)
                counts[touched[i]] = 0;
        }
        return out;
    }

}
