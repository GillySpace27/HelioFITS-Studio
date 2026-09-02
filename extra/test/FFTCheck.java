package org.helioviewer.jhv.image.fourier;

/**
 * The velocity filters select a wedge in (k, omega) by the sign and slope of a line, so the one
 * failure that matters in the transform is a sign or normalisation error: it still round-trips
 * perfectly, and it would silently swap inward and outward. Hence the single-bin exponential
 * (which bin, and with what amplitude), Parseval, and a synthetic moving feature whose power must
 * land at (k &gt; 0, omega &lt; 0) for v &gt; 0, alongside the ordinary round trips.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.image.fourier.FFTCheck
 */
public final class FFTCheck {

    private static int failures;

    public static void main(String[] args) {
        int n = 64;
        // impulse: flat unit magnitude
        float[] re = new float[n], im = new float[n];
        re[0] = 1;
        FFT.transform(re, im, n, false);
        expect("impulse is flat", maxAbsDiff(re, 1) < 1e-6 && maxAbs(im) < 1e-6);

        // exp(+2 pi i f j / n) lands in bin f with amplitude n: pins the forward sign
        int f = 5;
        for (int j = 0; j < n; j++) {
            re[j] = (float) Math.cos(2 * Math.PI * f * j / n);
            im[j] = (float) Math.sin(2 * Math.PI * f * j / n);
        }
        FFT.transform(re, im, n, false);
        expect("exponential lands in bin f", Math.abs(re[f] - n) < 1e-3 && Math.abs(im[f]) < 1e-3 && offBinEnergy(re, im, f, -1) < 1e-6);

        // real cosine: bins f and n - f, each n/2
        for (int j = 0; j < n; j++) {
            re[j] = (float) Math.cos(2 * Math.PI * f * j / n);
            im[j] = 0;
        }
        FFT.transform(re, im, n, false);
        expect("cosine splits into f and n-f", Math.abs(re[f] - n / 2.) < 1e-3 && Math.abs(re[n - f] - n / 2.) < 1e-3 && offBinEnergy(re, im, f, n - f) < 1e-6);

        // Parseval and round trip on noise
        java.util.Random rnd = new java.util.Random(7);
        float[] xr = new float[n], xi = new float[n];
        for (int j = 0; j < n; j++) {
            xr[j] = (float) rnd.nextGaussian();
            xi[j] = (float) rnd.nextGaussian();
        }
        double timeEnergy = energy(xr, xi);
        re = xr.clone();
        im = xi.clone();
        FFT.transform(re, im, n, false);
        expect("Parseval", Math.abs(energy(re, im) / n - timeEnergy) / timeEnergy < 1e-5);
        FFT.transform(re, im, n, true);
        expect("1D round trip", relError(xr, re) < 1e-5 && relError(xi, im) < 1e-5);

        // 2D and 3D round trips
        int nx = 32, ny = 16, nz = 8;
        float[] cr = new float[nx * ny * nz], ci = new float[nx * ny * nz];
        for (int i = 0; i < cr.length; i++) {
            cr[i] = (float) rnd.nextGaussian();
            ci[i] = (float) rnd.nextGaussian();
        }
        float[] r2 = java.util.Arrays.copyOf(cr, nx * ny), i2 = java.util.Arrays.copyOf(ci, nx * ny);
        FFT.transform2D(r2, i2, nx, ny, false);
        FFT.transform2D(r2, i2, nx, ny, true);
        expect("2D round trip", relError(java.util.Arrays.copyOf(cr, nx * ny), r2) < 1e-5);
        float[] r3 = cr.clone(), i3 = ci.clone();
        FFT.transform3D(r3, i3, nx, ny, nz, false);
        FFT.transform3D(r3, i3, nx, ny, nz, true);
        expect("3D round trip", relError(cr, r3) < 1e-5 && relError(ci, i3) < 1e-5);

        // A feature moving outward, I[t][r] = g(r - v t): peak power at k > 0, omega < 0 (and its conjugate).
        int nR = 64, nT = 32;
        float[] fr = new float[nR * nT], fi = new float[nR * nT];
        double v = 0.7; // samples per frame
        for (int t = 0; t < nT; t++)
            for (int r = 0; r < nR; r++) {
                double d = r - (10 + v * t);
                fr[t * nR + r] = (float) Math.exp(-d * d / 8);
            }
        FFT.transform2D(fr, fi, nR, nT, false);
        int best = -1;
        double bestP = -1;
        for (int b = 0; b < nT; b++)
            for (int a = 1; a < nR / 2; a++) { // k > 0, omega != 0 only
                if (FFT.signedIndex(b, nT) == 0)
                    continue;
                double p = fr[b * nR + a] * fr[b * nR + a] + fi[b * nR + a] * fi[b * nR + a];
                if (p > bestP) {
                    bestP = p;
                    best = b * nR + a;
                }
            }
        int a = best % nR, b = best / nR;
        expect("outward motion sits at k > 0, omega < 0 (slope " + (-(double) FFT.signedIndex(b, nT) / nT) / ((double) a / nR) + " samples/frame)",
                FFT.signedIndex(a, nR) > 0 && FFT.signedIndex(b, nT) < 0);

        System.out.println(failures == 0 ? "FFTCheck: PASS" : "FFTCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static double offBinEnergy(float[] re, float[] im, int keep1, int keep2) {
        double e = 0;
        for (int i = 0; i < re.length; i++)
            if (i != keep1 && i != keep2)
                e += re[i] * re[i] + im[i] * im[i];
        return e;
    }

    private static double energy(float[] re, float[] im) {
        double e = 0;
        for (int i = 0; i < re.length; i++)
            e += re[i] * re[i] + im[i] * im[i];
        return e;
    }

    private static double relError(float[] a, float[] b) {
        double num = 0, den = 0;
        for (int i = 0; i < a.length; i++) {
            num += (a[i] - b[i]) * (a[i] - b[i]);
            den += a[i] * a[i];
        }
        return Math.sqrt(num / den);
    }

    private static double maxAbsDiff(float[] a, double v) {
        double m = 0;
        for (float x : a)
            m = Math.max(m, Math.abs(x - v));
        return m;
    }

    private static double maxAbs(float[] a) {
        return maxAbsDiff(a, 0);
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private FFTCheck() {}

}
