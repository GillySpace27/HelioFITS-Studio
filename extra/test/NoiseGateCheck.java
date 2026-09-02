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
 * fallback on a short sequence.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.image.fourier.NoiseGateCheck
 */
public final class NoiseGateCheck {

    private static int failures;

    public static void main(String[] args) {
        int w = 96, h = 96, d = 32;
        Random rnd = new Random(3);
        float[] truth = blobs(w, h, d, 60, 240); // background 60, blob peaks 240 above it

        // (a) identity: gamma 0 opens every gate, so the doubly windowed overlap-add must reproduce the input, edges included
        {
            float[] noisy = truth.clone();
            NoiseGateParams p = new NoiseGateParams(NoiseGateParams.Model.SHOT, NoiseGateParams.Gate.HARD, 0, 50, 16, false);
            float[] out = run(noisy, w, h, d, p, null);
            expect(String.format("gamma 0 reproduces the input to 1e-4 (max rel err %.2e)", maxRelErr(noisy, out)), maxRelErr(noisy, out) < 1e-4);
        }

        // (b, c) shot noise: alpha sqrt(I) G with alpha 1.5, so the blob peaks sit at S/N ~ 240 / 26 per pixel but their faint edges near 1
        {
            float[] noisy = new float[truth.length];
            for (int i = 0; i < noisy.length; i++)
                noisy[i] = truth[i] + (float) (1.5 * Math.sqrt(truth[i]) * rnd.nextGaussian());
            NoiseGateParams p = new NoiseGateParams(NoiseGateParams.Model.SHOT, NoiseGateParams.Gate.HARD, 3, 50, 16, false);
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
            NoiseGateParams hard = new NoiseGateParams(NoiseGateParams.Model.ADDITIVE, NoiseGateParams.Gate.HARD, 3, 50, 16, false);
            float[] out = run(noisy, w, h, d, hard, null);
            double before = rms(noisy, truth, w, h, d), after = rms(out, truth, w, h, d);
            expect(String.format("additive hard gate cuts the residual by 3x or more (%.1f -> %.1f)", before, after), after < before / 3);
            NoiseGateParams wiener = new NoiseGateParams(NoiseGateParams.Model.ADDITIVE, NoiseGateParams.Gate.WIENER, 3, 50, 16, false);
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
            NoiseGateParams p = new NoiseGateParams(NoiseGateParams.Model.SHOT, NoiseGateParams.Gate.HARD, 3, 50, 16, false);
            NoiseGate.Setup s = NoiseGate.setup(16, d2);
            float[] out = run(noisy, w, h, d2, p, null);
            double before = rms(noisy, t2, w, h, d2), after = rms(out, t2, w, h, d2);
            expect(String.format("2D fallback (nt = %d) runs and improves the residual (%.1f -> %.1f)", s.nt(), before, after), s.nt() == 1 && after < before / 1.5);
        }

        System.out.println(failures == 0 ? "NoiseGateCheck: PASS" : "NoiseGateCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    // The whole pipeline on one volume, exactly as NoiseGateJob runs it on a tile.
    private static float[] run(float[] vol, int w, int h, int d, NoiseGateParams p, float[][] noiseOut) {
        NoiseGate.Setup s = NoiseGate.setup(p.n(), d);
        NoiseGate.Estimator est = new NoiseGate.Estimator(s, p.model() == NoiseGateParams.Model.SHOT, 2048);
        NoiseGate.estimateTile(vol, w, h, d, s, est);
        float[] noise = est.noise(p.percentile());
        if (noiseOut != null)
            noiseOut[0] = noise;
        return NoiseGate.gateTile(vol, w, h, d, s, noise, p);
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
