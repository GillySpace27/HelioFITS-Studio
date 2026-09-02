package org.helioviewer.jhv.image.fourier;

import org.helioviewer.jhv.thread.ParallelRange;

/**
 * The (k, omega) mask and its application to a PolarCube, one slice at a time.
 *
 * <p>With F(k, omega) = sum I(r, t) exp(-i (k r + omega t)), a feature f(r - v t) has its power on
 * omega = -v k, so the apparent rate of a Fourier component is v = -omega / k (km/s for RADIAL,
 * where k is in radians per km; Omega = -omega / m in rad/s for ANGULAR, m the azimuthal
 * wavenumber). The mask is 1 inside [lo, hi] of |rate|, 0 outside, with raised-cosine
 * transitions of 10 percent on either edge, times the direction test on the sign. PASS multiplies
 * by it, NOTCH by its complement; the static field (omega = 0) and modes uniform along the inner
 * axis (k = 0) have no rate and so are removed by PASS and kept by NOTCH.
 *
 * <p>Windows: Tukey (alpha 0.25) in t, and in r for RADIAL; none in phi, which is periodic. Hann
 * would halve the amplitude at the quarter points and the middle of the movie would visibly
 * fade, which reads as the filter doing something.
 */
public final class FourierFilter {

    static final double TUKEY_ALPHA = 0.25;
    static final double EDGE = 0.10; // relative width of the raised-cosine transition
    static final int SPECTRUM_BINS = 200;

    /** Power in the input as a function of rate, split by sign, on log-spaced bins over the resolvable range. */
    public record Spectrum(FourierParams.Kind kind, double[] rate, double[] powerPositive, double[] powerNegative) {}

    /** The band weight in [0, 1] for a rate magnitude x. */
    static double band(double x, double lo, double hi) {
        double lo0 = lo * (1 - EDGE), lo1 = lo * (1 + EDGE), hi0 = hi * (1 - EDGE), hi1 = hi * (1 + EDGE);
        if (x < lo0 || x > hi1)
            return 0;
        if (x >= lo1 && x <= hi0)
            return 1;
        if (x < lo1)
            return lo1 == lo0 ? 1 : 0.5 * (1 - Math.cos(Math.PI * (x - lo0) / (lo1 - lo0)));
        return 0.5 * (1 + Math.cos(Math.PI * (x - hi0) / (hi1 - hi0)));
    }

    /**
     * The mask over an (inner, t) plane of nInnerP x nTp bins, inner fastest. dInner is the inner
     * sample spacing (km for RADIAL, ignored for ANGULAR where the wavenumber is the integer m),
     * dt the time sample spacing in seconds.
     */
    static float[] mask(FourierParams p, int nInnerP, int nTp, double dInner, double dt) {
        float[] m = new float[nInnerP * nTp];
        boolean pass = p.mode() == FourierParams.Mode.PASS;
        for (int b = 0; b < nTp; b++) {
            double omega = 2 * Math.PI * FFT.signedIndex(b, nTp) / (nTp * dt);
            for (int a = 0; a < nInnerP; a++) {
                int sa = FFT.signedIndex(a, nInnerP);
                float w;
                if (sa == 0) {
                    w = pass ? 0 : 1;
                } else {
                    double k = p.kind() == FourierParams.Kind.RADIAL ? 2 * Math.PI * sa / (nInnerP * dInner) : sa;
                    double rate = -omega / k;
                    double dir = switch (p.direction()) {
                        case BOTH -> 1;
                        case POSITIVE -> rate > 0 ? 1 : 0;
                        case NEGATIVE -> rate < 0 ? 1 : 0;
                    };
                    double g = band(Math.abs(rate), p.lo(), p.hi()) * dir;
                    w = (float) (pass ? g : 1 - g);
                }
                m[b * nInnerP + a] = w;
            }
        }
        return m;
    }

    static float[] tukey(int n) {
        float[] w = new float[n];
        double edge = TUKEY_ALPHA * n / 2;
        for (int i = 0; i < n; i++) {
            double x = i + .5;
            double d = Math.min(x, n - x);
            w[i] = d >= edge ? 1 : (float) (0.5 * (1 - Math.cos(Math.PI * d / edge)));
        }
        return w;
    }

    /** Resolvable rate range {min, max} for the spectrum axis. dInner in km (RADIAL) or unused (ANGULAR). */
    static double[] rateRange(FourierParams.Kind kind, int nInner, int nT, double dInner, double dt) {
        return kind == FourierParams.Kind.RADIAL
                ? new double[]{2 * dInner / (nT * dt), nInner * dInner / (2 * dt)}
                : new double[]{2 * Math.PI / (nT * dt) / (nInner / 2.), Math.PI / dt};
    }

    /**
     * Filter every slice of the cube in place and return the input's rate spectrum. dInner is the
     * radial sample spacing in km (RADIAL) or ignored (ANGULAR); dt in seconds. Stops early,
     * leaving the cube half done, when the thread is interrupted; the caller checks.
     */
    public static Spectrum filterCube(PolarCube cube, FourierParams p, double dInner, double dt) {
        int nInner = cube.nInner, nT = cube.nT;
        int nInnerP = p.kind() == FourierParams.Kind.RADIAL ? FFT.nextPowerOfTwo(nInner) : nInner;
        int nTp = FFT.nextPowerOfTwo(nT);
        float[] mask = mask(p, nInnerP, nTp, dInner, dt);
        float[] wt = tukey(nT);
        float[] wr = p.kind() == FourierParams.Kind.RADIAL ? tukey(nInner) : null;

        double[] range = rateRange(p.kind(), nInner, nT, dInner, dt);
        double logMin = Math.log(range[0]), logMax = Math.log(range[1]);
        double[] rate = new double[SPECTRUM_BINS];
        for (int i = 0; i < SPECTRUM_BINS; i++)
            rate[i] = Math.exp(logMin + (logMax - logMin) * (i + .5) / SPECTRUM_BINS);
        double[] powPos = new double[SPECTRUM_BINS], powNeg = new double[SPECTRUM_BINS];

        ParallelRange.run(cube.nSlices, (from, to) -> {
            float[] re = new float[nInnerP * nTp], im = new float[nInnerP * nTp];
            double[] lpos = new double[SPECTRUM_BINS], lneg = new double[SPECTRUM_BINS];
            for (int s = from; s < to; s++) {
                if (Thread.currentThread().isInterrupted())
                    return;
                float[] d = cube.data[s];
                java.util.Arrays.fill(re, 0);
                java.util.Arrays.fill(im, 0);
                for (int t = 0; t < nT; t++)
                    for (int j = 0; j < nInner; j++)
                        re[t * nInnerP + j] = d[t * nInner + j] * wt[t] * (wr == null ? 1 : wr[j]);
                FFT.transform2D(re, im, nInnerP, nTp, false);
                // spectrum of the input, by rate
                for (int b = 0; b < nTp; b++) {
                    double omega = 2 * Math.PI * FFT.signedIndex(b, nTp) / (nTp * dt);
                    for (int a = 1; a < nInnerP; a++) {
                        int sa = FFT.signedIndex(a, nInnerP);
                        if (sa == 0)
                            continue;
                        double k = p.kind() == FourierParams.Kind.RADIAL ? 2 * Math.PI * sa / (nInnerP * dInner) : sa;
                        double r = -omega / k;
                        double ar = Math.abs(r);
                        if (ar < range[0] || ar > range[1])
                            continue;
                        int bin = Math.clamp((int) ((Math.log(ar) - logMin) / (logMax - logMin) * SPECTRUM_BINS), 0, SPECTRUM_BINS - 1);
                        int idx = b * nInnerP + a;
                        double pw = re[idx] * (double) re[idx] + im[idx] * (double) im[idx];
                        if (r > 0)
                            lpos[bin] += pw;
                        else
                            lneg[bin] += pw;
                    }
                }
                for (int i = 0; i < re.length; i++) {
                    re[i] *= mask[i];
                    im[i] *= mask[i];
                }
                FFT.transform2D(re, im, nInnerP, nTp, true);
                // back into the cube, the window divided out where it is not too small
                for (int t = 0; t < nT; t++) {
                    for (int j = 0; j < nInner; j++) {
                        double w = wt[t] * (wr == null ? 1 : wr[j]);
                        d[t * nInner + j] = w > 0.05 ? (float) (re[t * nInnerP + j] / w) : 0;
                    }
                }
            }
            synchronized (powPos) {
                for (int i = 0; i < SPECTRUM_BINS; i++) {
                    powPos[i] += lpos[i];
                    powNeg[i] += lneg[i];
                }
            }
        });
        return new Spectrum(p.kind(), rate, powPos, powNeg);
    }

    private FourierFilter() {}

}
