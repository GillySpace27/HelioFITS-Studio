package org.helioviewer.jhv.image;

import java.util.Arrays;

import org.helioviewer.jhv.metadata.Region;
import org.helioviewer.jhv.thread.ParallelRange;

// Radial Histogram Equalizing Filter (Gilly & DeForest 2024): rank-equalizes pixel
// values within ~1-pixel-wide annuli centered on the Sun, flattening the radial
// brightness gradient while preserving the relative structure at each height.
//
// The rank comes from a histogram rather than from sorting the pixels. ImageFilter hands this
// filter values that were half floats a moment ago, so there are at most 65536 of them and a bin
// per bit pattern is an enumeration of the possible values rather than a quantisation of them:
// the output is identical to the sort, not an approximation of it. What changes is the work, from
// n log n comparisons and n long-word swaps per annulus to two linear passes and a sort over the
// distinct values, which is the same reformulation that would let this run on a GPU one day.
class FilterRHEF implements ImageFilter.Algorithm {

    // Annuli with fewer valid pixels are passed through unfiltered
    private static final int MIN_BIN_COUNT = 5;

    private final SunCenteredRegion sunCenteredRegion;

    FilterRHEF(SunCenteredRegion _sunCenteredRegion) {
        sunCenteredRegion = _sunCenteredRegion;
    }

    @Override
    public float[] filter(float[] data, int width, int height) {
        if (width < 1 || height < 1)
            return data;

        // Buffer geometry in physical units; the region origin sits at the Sun center.
        // Without a region, assume the Sun at the image center with pixel units.
        Region region = sunCenteredRegion == null ? null : sunCenteredRegion.region();
        double pixX, pixY, llx, lly;
        if (region == null || !(region.width > 0) || !(region.height > 0)) {
            pixX = 1;
            pixY = 1;
            llx = -.5 * width;
            lly = -.5 * height;
        } else {
            pixX = region.width / width;
            pixY = region.height / height;
            llx = region.llx;
            lly = region.lly;
        }
        double invBinWidth = 1 / Math.min(pixX, pixY); // ~1-pixel-wide annuli

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
                for (int x = 0; x < width; x++) {
                    binOf[rowBase + x] = (int) (Math.sqrt(dx2[x] + dy2) * invBinWidth);
                }
            }
        });

        // Counting sort of pixel indices by annulus
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
            // One 65536-entry table per worker, reused across that worker's annuli and cleared
            // only where it was touched, so the per-annulus cost is the number of DISTINCT values
            // and never the size of the table.
            int[] counts = new int[1 << 16];
            float[] rankOf = new float[1 << 16];
            int[] touched = new int[0];
            for (int b = from; b < to; b++) {
                int lo = offset[b];
                int hi = offset[b + 1];
                if (hi - lo < MIN_BIN_COUNT)
                    continue;

                if (touched.length < hi - lo)
                    touched = new int[hi - lo];

                // Zero pixels (detector padding, occulters) are excluded and stay zero.
                int n = 0, distinct = 0;
                for (int j = lo; j < hi; j++) {
                    float v = data[order[j]];
                    if (!(v > 0))
                        continue;
                    int bits = Float.floatToFloat16(v) & 0xFFFF;
                    if (counts[bits]++ == 0)
                        touched[distinct++] = bits;
                    n++;
                }
                if (n < MIN_BIN_COUNT) {
                    for (int i = 0; i < distinct; i++)
                        counts[touched[i]] = 0;
                    continue;
                }

                // Ascending by bit pattern is ascending by value: these are all positive halves,
                // and for positive floating point the bit pattern orders numerically.
                Arrays.sort(touched, 0, distinct);
                float invRange = 1f / (n - 1);
                int cumulative = 0;
                for (int i = 0; i < distinct; i++) {
                    int bits = touched[i];
                    int c = counts[bits];
                    // The average rank of a run of equal values, which is what ranking every
                    // pixel and averaging the ties would give: scipy.stats.rankdata("average").
                    rankOf[bits] = .5f * (2 * cumulative + c - 1) * invRange;
                    cumulative += c;
                }

                for (int j = lo; j < hi; j++) {
                    int idx = order[j];
                    float v = data[idx];
                    if (v > 0)
                        out[idx] = rankOf[Float.floatToFloat16(v) & 0xFFFF];
                }
                for (int i = 0; i < distinct; i++)
                    counts[touched[i]] = 0;
            }
        });
        return out;
    }

}
