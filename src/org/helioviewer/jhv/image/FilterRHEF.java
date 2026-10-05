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
// RHEF-CONVENTION: oRHEF-2.0; deviations: RANK-N1, POS-ONLY, FP16, MIN-BIN, GEOM-PX, UPS-EXT, DTYPE-IN
class FilterRHEF implements ImageFilter.Algorithm {

    // Annuli with fewer valid pixels are passed through unfiltered
    private static final int MIN_BIN_COUNT = 5;

    private final SunCenteredRegion sunCenteredRegion;

    FilterRHEF(SunCenteredRegion _sunCenteredRegion) {
        sunCenteredRegion = _sunCenteredRegion;
    }

    /** Which annulus each pixel is in, and the pixels grouped by annulus: what both paths rank over. */
    private record Annuli(int numBins, int[] offset, int[] order) {}

    private Annuli annuli(int width, int height) {
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

        // Counting sort of pixel indices by annulus, in parallel over blocks of rows. Each block
        // counts its own pixels per annulus, and within an annulus the blocks are laid down in row
        // order, so the result is the same array the serial two-pass sort produced: every annulus
        // holds its pixels in ascending index order.
        int blocks = Math.min(PROCESSORS, height);
        int[][] count = new int[blocks][numBins];
        ParallelRange.run(blocks, (from, to) -> {
            for (int b = from; b < to; b++) {
                int[] c = count[b];
                for (int i = b * height / blocks * width, end = (b + 1) * height / blocks * width; i < end; i++)
                    c[binOf[i]]++;
            }
        });
        int[] offset = new int[numBins + 1];
        for (int bin = 0; bin < numBins; bin++) {
            int at = offset[bin];
            for (int b = 0; b < blocks; b++) {
                int c = count[b][bin];
                count[b][bin] = at; // from here on, where block b writes its next pixel of this annulus
                at += c;
            }
            offset[bin + 1] = at;
        }
        int[] order = new int[length];
        ParallelRange.run(blocks, (from, to) -> {
            for (int b = from; b < to; b++) {
                int[] cursor = count[b];
                for (int i = b * height / blocks * width, end = (b + 1) * height / blocks * width; i < end; i++)
                    order[cursor[binOf[i]]++] = i;
            }
        });
        return new Annuli(numBins, offset, order);
    }

    private static final int PROCESSORS = Runtime.getRuntime().availableProcessors();

    @Override
    public float[] filter(float[] data, int width, int height) {
        if (width < 1 || height < 1)
            return data;

        Annuli a = annuli(width, height);
        int numBins = a.numBins();
        int[] offset = a.offset();
        int[] order = a.order();

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
                        data[idx] = rankOf[Float.floatToFloat16(v) & 0xFFFF];
                }
                for (int i = 0; i < distinct; i++)
                    counts[touched[i]] = 0;
            }
        });
        return data;
    }

    /**
     * The same ranks, straight from the half floats ImageFilter was given.
     *
     * <p>filter() receives these as floats and turns each one back into its half-float bit pattern
     * twice; the pattern is exactly the half the value came from, so this path starts from the
     * halves. What it returns is what filter() returned for the same input: the rank for every
     * positive pixel of an annulus with enough of them, and the value as a float everywhere else.
     * "Positive" is the half pattern 0x0001 to 0x7C00 (infinity included), which is what v > 0
     * selects from the floats; zero, negatives and NaN stay as they are.
     *
     * <p>The distinct values of an annulus come out of a bitset in ascending order instead of being
     * sorted, and an annulus's halves are gathered once into a scratch array instead of being read
     * twice from wherever its pixels lie in the image. Annuli are handed out by pixel count rather
     * than by index, since the rings near the corners hold many more pixels than the ones near the
     * center.
     */
    @Override
    public float[] filterHalf(short[] halves, int width, int height) {
        int length = width * height;
        float[] out = new float[length];
        ParallelRange.run(height, (from, to) -> {
            for (int i = from * width, end = to * width; i < end; i++)
                out[i] = Float.float16ToFloat(halves[i]);
        });
        if (width < 1 || height < 1)
            return out;

        Annuli a = annuli(width, height);
        int numBins = a.numBins();
        int[] offset = a.offset();
        int[] order = a.order();

        int parts = Math.min(PROCESSORS * 4, numBins);
        int[] firstBin = new int[parts + 1];
        for (int k = 1, bin = 0; k < parts; k++) {
            long target = (long) length * k / parts;
            while (bin < numBins && offset[bin] < target)
                bin++;
            firstBin[k] = bin;
        }
        firstBin[parts] = numBins;

        ParallelRange.run(parts, (from, to) -> {
            int[] counts = new int[1 << 16];
            float[] rankOf = new float[1 << 16];
            long[] present = new long[1 << 10];
            short[] ring = new short[0];
            for (int k = from; k < to; k++) {
                for (int bin = firstBin[k]; bin < firstBin[k + 1]; bin++) {
                    int lo = offset[bin];
                    int hi = offset[bin + 1];
                    if (hi - lo < MIN_BIN_COUNT)
                        continue;
                    if (ring.length < hi - lo)
                        ring = new short[hi - lo];

                    int n = 0, minWord = Integer.MAX_VALUE, maxWord = -1;
                    for (int j = lo; j < hi; j++) {
                        short h = halves[order[j]];
                        ring[j - lo] = h;
                        int bits = h & 0xFFFF;
                        if (((bits - 1) & 0xFFFF) >= 0x7C00) // not in 0x0001..0x7C00 (zero wraps to 0xFFFF): not positive
                            continue;
                        if (counts[bits]++ == 0) {
                            int w = bits >>> 6;
                            present[w] |= 1L << bits;
                            minWord = Math.min(minWord, w);
                            maxWord = Math.max(maxWord, w);
                        }
                        n++;
                    }

                    boolean rank = n >= MIN_BIN_COUNT;
                    float invRange = 1f / (n - 1);
                    int cumulative = 0;
                    for (int w = minWord; w <= maxWord; w++) {
                        long word = present[w];
                        present[w] = 0;
                        while (word != 0) {
                            int bits = w << 6 | Long.numberOfTrailingZeros(word);
                            word &= word - 1;
                            int c = counts[bits];
                            counts[bits] = 0;
                            if (rank) {
                                // The average rank of a run of equal values, as filter() computes it.
                                rankOf[bits] = .5f * (2 * cumulative + c - 1) * invRange;
                                cumulative += c;
                            }
                        }
                    }
                    if (!rank)
                        continue;

                    for (int j = lo; j < hi; j++) {
                        int bits = ring[j - lo] & 0xFFFF;
                        if (((bits - 1) & 0xFFFF) < 0x7C00)
                            out[order[j]] = rankOf[bits];
                    }
                }
            }
        });
        return out;
    }

}
