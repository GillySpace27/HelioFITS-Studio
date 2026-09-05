package org.helioviewer.jhv.image.fourier;

import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.metadata.Region;

/**
 * Where the velocity filter's time actually goes, at the size that prompted the question.
 *
 * <p>Not a check: it asserts nothing and is not part of the suite. It exists so a claim about the
 * bottleneck is a measurement rather than a guess. The observed run it is calibrated against is
 * 245 PUNCH mosaic frames of 4096 x 4096 in 114 s (log, 2026-09-05).
 *
 * <p>Run: java -Xmx24g -cp bin:extra/test-classes org.helioviewer.jhv.image.fourier.FourierProfile
 */
public final class FourierProfile {

    public static void main(String[] args) {
        int w = 4096, h = 4096, frames = 245;
        int nR = 1024, nPhi = 512, nT = 256;
        Region region = new Region(-32, -32, 64, 64); // a PUNCH mosaic's field, in solar radii

        PolarCube cube = new PolarCube(FourierParams.Kind.RADIAL, nR, nPhi, nT, 12, (32. - 12) / nR);
        float[] frame = new float[w * h];
        for (int i = 0; i < frame.length; i++)
            frame[i] = (i % 977) / 977f;

        // Warm up: the first pass through any of this is JIT compilation, not work.
        cube.put(0, frame, w, h, region);
        cube.toCartesian(frame, 512, 512, region, 0, false);

        long t0 = System.nanoTime();
        for (int i = 0; i < 4; i++)
            cube.put(i, frame, w, h, region);
        double putMs = (System.nanoTime() - t0) / 4e6;

        float[] out = new float[w * h];
        t0 = System.nanoTime();
        for (int i = 0; i < 2; i++)
            cube.toCartesian(out, w, h, region, i, false);
        double backMs = (System.nanoTime() - t0) / 2e6;

        // The transform, on one slice, scaled to the whole cube. filterCube parallelises over
        // slices, so the single-slice cost is divided by the core count to compare fairly.
        int cores = Runtime.getRuntime().availableProcessors();
        float[] re = new float[nT], im = new float[nT];
        t0 = System.nanoTime();
        int reps = 20000;
        for (int i = 0; i < reps; i++) {
            FFT.transform(re, im, nT, false);
            FFT.transform(re, im, nT, true);
        }
        double lineUs = (System.nanoTime() - t0) / (reps * 1e3);
        double fftMs = lineUs * nR * nPhi / 1e3 / cores;

        // What the same work costs with the two obvious changes: sqrt instead of Math.hypot,
        // which is carefully-rounded and far slower for no benefit here, and the row loop spread
        // over the cores. Same arithmetic otherwise, so the comparison is like for like.
        t0 = System.nanoTime();
        for (int i = 0; i < 2; i++)
            fastBack(cube, out, w, h, region, i);
        double fastMs = (System.nanoTime() - t0) / 2e6;

        // A control: this same private loop WITH hypot, single-threaded, which should land on
        // top of the real toCartesian and so show the loop is a faithful copy.
        t0 = System.nanoTime();
        for (int i = 0; i < 2; i++)
            back(cube, out, w, h, region, i, 0, h, true);
        double controlMs = (System.nanoTime() - t0) / 2e6;

        // And each change on its own, to say which one carries it.
        t0 = System.nanoTime();
        for (int i = 0; i < 2; i++)
            sqrtOnlyBack(cube, out, w, h, region, i);
        double sqrtMs = (System.nanoTime() - t0) / 2e6;

        System.out.printf("cores %d%n", cores);
        System.out.printf("put         %8.1f ms per uniform sample  x %4d = %6.1f s%n", putMs, nT, putMs * nT / 1000);
        System.out.printf("toCartesian %8.1f ms per frame           x %4d = %6.1f s%n", backMs, frames, backMs * frames / 1000);
        System.out.printf("FFT (t)     %8.3f us per line, %d lines, %d cores = %6.1f s%n", lineUs, nR * nPhi, cores, fftMs / 1000 * 1000 / 1000);
        System.out.printf("%nback-projection is %.0f%% of put+back%n", 100 * backMs * frames / (backMs * frames + putMs * nT));
        outputStage(w, h, frames, nT);

        System.out.printf("%ntoCartesian as written        %8.1f ms/frame  -> %6.1f s%n", backMs, backMs * frames / 1000);
        System.out.printf("  same loop copied here       %8.1f ms/frame  (control: should match)%n", controlMs);
        System.out.printf("  with sqrt instead of hypot  %8.1f ms/frame  -> %6.1f s%n", sqrtMs, sqrtMs * frames / 1000);
        System.out.printf("  and spread over the cores   %8.1f ms/frame  -> %6.1f s%n", fastMs, fastMs * frames / 1000);
    }

    /** The same loop with Math.hypot replaced; nothing else changed. */
    private static void sqrtOnlyBack(PolarCube cube, float[] out, int w, int h, Region sc, double u) {
        back(cube, out, w, h, sc, u, 0, h, false);
    }

    /** The same loop again, with the rows handed to the common pool. */
    private static void fastBack(PolarCube cube, float[] out, int w, int h, Region sc, double u) {
        org.helioviewer.jhv.thread.ParallelRange.run(h, (from, to) -> back(cube, out, w, h, sc, u, from, to, false));
    }

    /**
     * Everything the job does per frame that is not the resample.
     *
     * <p>These are the production inner loops replicated here rather than called, because
     * FrameStack needs a live View and a MetaData to hand out a Frame. The arithmetic and the
     * allocations are the same, so the shape of the answer is; treat the absolute numbers as
     * this machine's, which is the only claim being made about them.
     */
    private static void outputStage(int w, int h, int frames, int nT) {
        int px = w * h;
        java.nio.ShortBuffer stored = java.nio.ShortBuffer.allocate(px);
        for (int i = 0; i < px; i++)
            stored.put(i, Float.floatToFloat16((i % 977) / 977f));
        float[] lut = new float[1 << 16];
        for (int bits = 0; bits < lut.length; bits++) {
            float d = Float.float16ToFloat((short) bits);
            lut[bits] = !(d > 0) || d > 1 ? Float.NaN : d * 1e-14f;
        }

        // 1. Half to physical through the 65536-entry LUT: FrameStack.physical.
        float[] phys = new float[px];
        long t = System.nanoTime();
        for (int rep = 0; rep < 3; rep++)
            for (int i = 0; i < px; i++)
                phys[i] = lut[stored.get(i) & 0xFFFF];
        double lutMs = (System.nanoTime() - t) / 3e6;

        // 2. The float[w*h] the job allocates fresh for every frame and every uniform sample.
        t = System.nanoTime();
        float[] sink = null;
        for (int rep = 0; rep < 8; rep++)
            sink = new float[px];
        double allocMs = (System.nanoTime() - t) / 8e6;
        if (sink == null)
            throw new AssertionError();

        // 3. The mask copy: a whole pass over the frame to carry the source's NaNs across.
        float[] values = new float[px];
        t = System.nanoTime();
        for (int rep = 0; rep < 3; rep++)
            for (int i = 0; i < px; i++)
                if (Float.isNaN(phys[i]))
                    values[i] = Float.NaN;
        double maskMs = (System.nanoTime() - t) / 3e6;

        // 4a. packSigned, which is what a PASS output uses.
        short[] half = new short[px];
        double inv = 0.5 / 1e-13;
        t = System.nanoTime();
        for (int rep = 0; rep < 3; rep++)
            for (int i = 0; i < px; i++) {
                float v = values[i];
                half[i] = Float.isNaN(v) ? 0 : Float.floatToFloat16((float) Math.clamp(0.5 + v * inv, 1e-6, 1));
            }
        double packSignedMs = (System.nanoTime() - t) / 3e6;

        // 4b. packLike, which is what a NOTCH output uses: a lambda call per pixel.
        ImageBuffer.PhysicalScale scale = new ImageBuffer.PhysicalScale(0, 1e-12f, x -> x, "linear", x -> Math.sqrt(x));
        t = System.nanoTime();
        for (int rep = 0; rep < 3; rep++)
            for (int i = 0; i < px; i++) {
                float v = values[i];
                if (Float.isNaN(v)) {
                    half[i] = 0;
                    continue;
                }
                half[i] = Float.floatToFloat16((float) Math.max(1e-6, Math.min(1, scale.toDisplay(v))));
            }
        double packLikeMs = (System.nanoTime() - t) / 3e6;

        // 5. The linear interpolation between two frames, run once per uniform sample.
        float[] other = new float[px];
        t = System.nanoTime();
        for (int rep = 0; rep < 3; rep++)
            for (int i = 0; i < px; i++)
                values[i] = (float) (0.4 * phys[i] + 0.6 * other[i]);
        double interpMs = (System.nanoTime() - t) / 3e6;

        // 6. What the mask costs if it is read straight off the stored halves instead of being
        // recovered by decoding the frame a second time: a short compare, no LUT, no float array.
        t = System.nanoTime();
        for (int rep = 0; rep < 3; rep++)
            for (int i = 0; i < px; i++)
                if (stored.get(i) == 0)
                    values[i] = Float.NaN;
        double maskCheapMs = (System.nanoTime() - t) / 3e6;

        System.out.printf("%n-- per frame, the work that is not the resample --%n");
        System.out.printf("half->physical LUT pass  %7.1f ms  x %d passes = %6.1f s%n", lutMs, 2 * frames, lutMs * 2 * frames / 1000);
        System.out.printf("float[w*h] allocation    %7.1f ms  x %d        = %6.1f s%n", allocMs, 2 * frames + nT, allocMs * (2 * frames + nT) / 1000);
        System.out.printf("NaN mask copy            %7.1f ms  x %d        = %6.1f s%n", maskMs, frames, maskMs * frames / 1000);
        System.out.printf("packSigned (PASS)        %7.1f ms  x %d        = %6.1f s%n", packSignedMs, frames, packSignedMs * frames / 1000);
        System.out.printf("packLike   (NOTCH)       %7.1f ms  x %d        = %6.1f s%n", packLikeMs, frames, packLikeMs * frames / 1000);
        System.out.printf("frame interpolation      %7.1f ms  x %d        = %6.1f s%n", interpMs, nT, interpMs * nT / 1000);
        System.out.printf("%nthe mask from stored halves instead of a second decode: %.1f ms vs %.1f ms + %.1f ms%n",
                maskCheapMs, lutMs, maskMs);
    }

    /**
     * The real loop, byte for byte, except that Math.hypot is a square root and the rows can be
     * split. Same sampling, same mean, so a difference is the change and not a missing step.
     */
    private static void back(PolarCube cube, float[] out, int w, int h, Region sc, double u, int rowFrom, int rowTo, boolean useHypot) {
        double pixX = sc.width / w, pixY = sc.height / h;
        double rIn = 12, dr = (32. - 12) / 1024, rOut = rIn + 1024 * dr;
        double dPhi = 2 * Math.PI / 512;
        int t0 = Math.clamp((int) Math.floor(u), 0, 255);
        int t1 = Math.min(t0 + 1, 255);
        double ft = Math.clamp(u - t0, 0, 1);
        for (int y = rowFrom; y < rowTo; y++) {
            double dy = sc.lly + (y + .5) * pixY;
            for (int x = 0; x < w; x++) {
                double dx = sc.llx + (x + .5) * pixX;
                double r = useHypot ? Math.hypot(dx, dy) : Math.sqrt(dx * dx + dy * dy);
                int idx = y * w + x;
                if (r < rIn || r >= rOut) {
                    out[idx] = Float.NaN;
                    continue;
                }
                double phi = Math.atan2(-dx, -dy);
                if (phi < 0)
                    phi += 2 * Math.PI;
                double ir = (r - rIn) / dr - .5;
                double iphi = phi / dPhi - .5;
                float a = cube.sample(t0, ir, iphi);
                float b = t1 == t0 ? a : cube.sample(t1, ir, iphi);
                out[idx] = Float.isNaN(a) || Float.isNaN(b) ? Float.NaN : (float) ((1 - ft) * a + ft * b);
            }
        }
    }

}
