package org.helioviewer.jhv.image.fourier;

import java.util.Random;

/**
 * A gate that is always open or always closed both look like "something happened" in a movie,
 * and a wrong overlap-add constant or edge pad gives a cleanly scaled picture that looks right.
 * So: with gamma 0 (every gate open) the output must equal the input everywhere, edges included;
 * on a synthetic sequence of drifting blobs with shot noise the estimated noise spectrum must be
 * flat, the residual against the known clean field must drop by a factor of at least three, the
 * blobs' integrals must survive, and the removed part must not correlate with the truth (the
 * paper's "no structure in the difference"). The same for additive noise, and for the 2D
 * fallback on a short sequence. Two more: gating a volume whole and gating it in two tiles with
 * real halos must give the same pixels (a tile seam is a failure that shows only as a faint line
 * every 256 px), and with noise that grows with radius the banded estimate must track it and
 * beat the single-level estimate.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.image.fourier.NoiseGateCheck
 */
public final class NoiseGateCheck {

    private static int failures;

    public static void main(String[] args) {
        // 160 pixels, not 96: the estimate needs most of its neighbourhoods noise-dominated at every
        // component (the paper's condition), and on 96 pixels the four blobs touch a third of them.
        int w = 160, h = 160, d = 32;
        Random rnd = new Random(3);
        float[] truth = blobs(w, h, d, 60, 240); // background 60, blob peaks 240 above it

        // (a) identity: gamma 0 opens every gate, so the doubly windowed overlap-add must reproduce the input, edges included
        {
            float[] noisy = truth.clone();
            NoiseGateParams p = new NoiseGateParams(NoiseGateParams.Model.SHOT, NoiseGateParams.Gate.HARD, 0, 50, 16, false, 0);
            float[] out = run(noisy, w, h, d, p, null);
            expect(String.format("gamma 0 reproduces the input to 1e-4 (max rel err %.2e)", maxRelErr(noisy, out)), maxRelErr(noisy, out) < 1e-4);
        }

        // (b, c) shot noise: alpha sqrt(I) G with alpha 1.5, so the blob peaks sit at S/N ~ 240 / 26 per pixel but their faint edges near 1
        {
            float[] noisy = new float[truth.length];
            for (int i = 0; i < noisy.length; i++)
                noisy[i] = truth[i] + (float) (1.5 * Math.sqrt(truth[i]) * rnd.nextGaussian());
            NoiseGateParams p = new NoiseGateParams(NoiseGateParams.Model.SHOT, NoiseGateParams.Gate.HARD, 3, 50, 16, false, 0);
            float[][] noiseOut = new float[1][];
            float[] out = run(noisy, w, h, d, p, noiseOut);
            double flat = flatness(noiseOut[0], 16 * 16 * 16);
            expect(String.format("shot-noise spectrum estimate is flat (scatter %.0f percent)", 100 * flat), flat < 0.35);
            double before = rms(noisy, truth, w, h, d), after = rms(out, truth, w, h, d);
            expect(String.format("shot gate cuts the residual by 3x or more (%.1f -> %.1f)", before, after), after < before / 3);
            expect(String.format("blob integrals within 5 percent (%.1f percent)", 100 * integralError(out, truth, w, h, d, 60)), integralError(out, truth, w, h, d, 60) < 0.05);
            double rho = correlation(noisy, out, truth, w, h, d);
            expect(String.format("what was removed does not correlate with the truth (rho %.3f)", rho), Math.abs(rho) < 0.1);
        }

        // (d) additive noise: the hard gate to the same standard, and the Wiener gate, which the
        // paper says admits more noise at a given threshold, still a clear improvement
        {
            float[] noisy = new float[truth.length];
            for (int i = 0; i < noisy.length; i++)
                noisy[i] = truth[i] + (float) (20 * rnd.nextGaussian());
            NoiseGateParams hard = new NoiseGateParams(NoiseGateParams.Model.ADDITIVE, NoiseGateParams.Gate.HARD, 3, 50, 16, false, 0);
            float[] out = run(noisy, w, h, d, hard, null);
            double before = rms(noisy, truth, w, h, d), after = rms(out, truth, w, h, d);
            expect(String.format("additive hard gate cuts the residual by 3x or more (%.1f -> %.1f)", before, after), after < before / 3);
            NoiseGateParams wiener = new NoiseGateParams(NoiseGateParams.Model.ADDITIVE, NoiseGateParams.Gate.WIENER, 3, 50, 16, false, 0);
            double afterW = rms(run(noisy, w, h, d, wiener, null), truth, w, h, d);
            expect(String.format("additive Wiener gate cuts the residual by 1.5x or more (%.1f -> %.1f)", before, afterW), afterW < before / 1.5);
        }

        // (e) too few frames for a 3D neighbourhood: the 2D per-frame gate still runs and still helps
        {
            int d2 = 8;
            float[] t2 = blobs(w, h, d2, 60, 240);
            float[] noisy = new float[t2.length];
            for (int i = 0; i < noisy.length; i++)
                noisy[i] = t2[i] + (float) (1.5 * Math.sqrt(t2[i]) * rnd.nextGaussian());
            NoiseGateParams p = new NoiseGateParams(NoiseGateParams.Model.SHOT, NoiseGateParams.Gate.HARD, 3, 50, 16, false, 0);
            NoiseGate.Setup s = NoiseGate.setup(16, d2);
            float[] out = run(noisy, w, h, d2, p, null);
            double before = rms(noisy, t2, w, h, d2), after = rms(out, t2, w, h, d2);
            expect(String.format("2D fallback (nt = %d) runs and improves the residual (%.1f -> %.1f)", s.nt(), before, after), s.nt() == 1 && after < before / 1.5);
        }

        // (f) tiles with real halos are invisible: whole == tiles, same noise spectrum, to float precision
        {
            float[] noisy = new float[truth.length];
            for (int i = 0; i < noisy.length; i++)
                noisy[i] = truth[i] + (float) (1.5 * Math.sqrt(truth[i]) * rnd.nextGaussian());
            NoiseGateParams p = new NoiseGateParams(NoiseGateParams.Model.SHOT, NoiseGateParams.Gate.HARD, 3, 50, 16, false, 0);
            NoiseGate.Setup s = NoiseGate.setup(16, d);
            int halo = NoiseGate.halo(s);
            NoiseGate.Estimator est = new NoiseGate.Estimator(s, true, 2048, 0);
            NoiseGate.estimateTile(noisy, w, h, d, s, est, 0, null);
            float[][] noise = est.noise(50);
            float[] whole = NoiseGate.gateTile(noisy, w, h, d, s, noise, p, 0, null);
            // two tiles of 80 columns, each with a halo of real (or, at the image edge, reflected) data
            float[] tiled = new float[whole.length];
            for (int tx = 0; tx < 2; tx++) {
                int x0 = tx * 80, tw = 80 + 2 * halo, th = h + 2 * halo;
                float[] vol = new float[tw * th * d];
                for (int z = 0; z < d; z++)
                    for (int y = 0; y < th; y++)
                        for (int x = 0; x < tw; x++)
                            vol[(z * th + y) * tw + x] = noisy[(z * h + NoiseGate.reflect(y - halo, h)) * w + NoiseGate.reflect(x0 + x - halo, w)];
                float[] out = NoiseGate.gateTile(vol, tw, th, d, s, noise, p, halo, null);
                for (int z = 0; z < d; z++)
                    for (int y = 0; y < h; y++)
                        System.arraycopy(out, (z * h + y) * 80, tiled, (z * h + y) * w + x0, 80);
            }
            expect(String.format("two haloed tiles equal the whole (max rel diff %.1e)", maxRelErr(whole, tiled)), maxRelErr(whole, tiled) < 1e-4);
        }

        // (g) noise growing with radius on a frame big enough for four bands to hold hundreds of
        // neighbourhoods each: the banded estimate follows it, and gating with bands does better than one level
        {
            int gw = 192, gh = 192;
            float[] gt = blobs(gw, gh, d, 60, 240); // the blobs sit in the top-left quadrant: the inner bands are noise only
            double cx = gw / 2., cy = gh / 2., rMax = Math.hypot(cx, cy);
            float[] noisy = new float[gt.length];
            for (int z = 0; z < d; z++)
                for (int y = 0; y < gh; y++)
                    for (int x = 0; x < gw; x++) {
                        int i = (z * gh + y) * gw + x;
                        double sigma = 5 + 25 * Math.hypot(x - cx, y - cy) / rMax; // 5 at the centre, 30 at the corner
                        noisy[i] = gt[i] + (float) (sigma * rnd.nextGaussian());
                    }
            NoiseGate.Setup s = NoiseGate.setup(16, d);
            NoiseGate.Radial radial = new NoiseGate.Radial(cx, cy, rMax, 4);
            NoiseGate.Estimator est = new NoiseGate.Estimator(s, false, 2048, 4);
            NoiseGate.estimateTile(noisy, gw, gh, d, s, est, 0, radial);
            float[][] noise = est.noise(50);
            // band 0 is four lattice positions on this frame and falls back to the global level; bands 1 and 3
            // have hundreds, and their centres sit where the noise differs by 1.9
            double inner = median(noise[1]), outer = median(noise[3]);
            expect(String.format("banded estimate grows outward (band 3 / band 1 = %.2f, expected about 1.9)", outer / inner), outer / inner > 1.5 && outer / inner < 2.4);
            NoiseGateParams banded = new NoiseGateParams(NoiseGateParams.Model.ADDITIVE, NoiseGateParams.Gate.HARD, 3, 50, 16, false, 4);
            NoiseGateParams flat = new NoiseGateParams(NoiseGateParams.Model.ADDITIVE, NoiseGateParams.Gate.HARD, 3, 50, 16, false, 0);
            float[] withBands = NoiseGate.gateTile(noisy, gw, gh, d, s, noise, banded, 0, radial);
            float[][] one = {noise[4]};
            float[] oneLevel = NoiseGate.gateTile(noisy, gw, gh, d, s, one, flat, 0, null);
            double rb = rms(withBands, gt, gw, gh, d), ro = rms(oneLevel, gt, gw, gh, d), before = rms(noisy, gt, gw, gh, d);
            expect(String.format("radial bands beat one level (%.1f -> %.1f banded, %.1f one level)", before, rb, ro), rb < ro && rb < before / 2);
        }

        System.out.println(failures == 0 ? "NoiseGateCheck: PASS" : "NoiseGateCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    // The whole pipeline on one volume, exactly as NoiseGateJob runs it on a tile.
    private static float[] run(float[] vol, int w, int h, int d, NoiseGateParams p, float[][] noiseOut) {
        NoiseGate.Setup s = NoiseGate.setup(p.n(), d);
        NoiseGate.Estimator est = new NoiseGate.Estimator(s, p.model() == NoiseGateParams.Model.SHOT, 2048, 0);
        NoiseGate.estimateTile(vol, w, h, d, s, est, 0, null);
        float[][] noise = est.noise(p.percentile());
        if (noiseOut != null)
            noiseOut[0] = noise[0];
        return NoiseGate.gateTile(vol, w, h, d, s, noise, p, 0, null);
    }

    private static double median(float[] a) {
        float[] c = java.util.Arrays.copyOfRange(a, 1, a.length); // skip the DC component
        java.util.Arrays.sort(c);
        return c[c.length / 2];
    }

    // Gaussian blobs drifting through the volume over a flat background.
    private static float[] blobs(int w, int h, int d, double background, double peak) {
        float[] v = new float[w * h * d];
        double[][] c = {{20, 25, 0.6, 0.3}, {60, 70, -0.5, 0.4}, {70, 20, 0.2, -0.6}, {30, 60, -0.3, -0.2}};
        for (int z = 0; z < d; z++)
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    double s = background;
                    for (double[] b : c) {
                        double dx = x - (b[0] + b[2] * z), dy = y - (b[1] + b[3] * z);
                        s += peak * Math.exp(-(dx * dx + dy * dy) / (2 * 4.5 * 4.5));
                    }
                    v[(z * h + y) * w + x] = (float) s;
                }
        return v;
    }

    private static double maxRelErr(float[] a, float[] b) {
        double m = 0;
        for (int i = 0; i < a.length; i++)
            m = Math.max(m, Math.abs(a[i] - b[i]) / Math.max(1e-6, Math.abs(a[i])));
        return m;
    }

    private static double rms(float[] a, float[] truth, int w, int h, int d) {
        double s = 0;
        int c = 0;
        for (int z = 0; z < d; z++)
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int i = (z * h + y) * w + x;
                    s += (a[i] - truth[i]) * (a[i] - truth[i]);
                    c++;
                }
        return Math.sqrt(s / c);
    }

    // scatter of the noise estimate over the non-DC components: std / mean
    private static double flatness(float[] noise, int comps) {
        double s = 0, s2 = 0;
        int c = 0;
        for (int k = 1; k < comps; k++) {
            s += noise[k];
            s2 += noise[k] * (double) noise[k];
            c++;
        }
        double mean = s / c;
        return Math.sqrt(Math.max(0, s2 / c - mean * mean)) / mean;
    }

    // relative error of the summed excess above background inside the blobs
    private static double integralError(float[] out, float[] truth, int w, int h, int d, double background) {
        double so = 0, st = 0;
        for (int i = 0; i < out.length; i++)
            if (truth[i] > background + 30) {
                so += out[i] - background;
                st += truth[i] - background;
            }
        return Math.abs(so - st) / st;
    }

    // correlation between (noisy - out) and (truth - mean) over the volume
    private static double correlation(float[] noisy, float[] out, float[] truth, int w, int h, int d) {
        double mr = 0, mt = 0;
        int c = noisy.length;
        for (int i = 0; i < c; i++) {
            mr += noisy[i] - out[i];
            mt += truth[i];
        }
        mr /= c;
        mt /= c;
        double srt = 0, srr = 0, stt = 0;
        for (int i = 0; i < c; i++) {
            double r = noisy[i] - out[i] - mr, t = truth[i] - mt;
            srt += r * t;
            srr += r * r;
            stt += t * t;
        }
        return srt / Math.sqrt(srr * stt);
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private NoiseGateCheck() {}

}
