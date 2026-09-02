package org.helioviewer.jhv.image.fourier;

/**
 * A filter that returns its input passes any visual test on a real movie: something is on
 * screen, it moves, the sliders do something. This builds cubes whose content is known to the
 * component: a feature going out at 300 km/s, one coming in at 300 km/s, and a static ring; a
 * pattern rotating at an orbital rate next to a static m = 4 pattern. Pass, notch and direction
 * each have to keep and remove the right ones, and the rate spectrum has to peak where the
 * feature is.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.image.fourier.FourierFilterCheck
 */
public final class FourierFilterCheck {

    private static int failures;

    public static void main(String[] args) {
        radial();
        angular();
        System.out.println(failures == 0 ? "FourierFilterCheck: PASS" : "FourierFilterCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void radial() {
        // 512 samples of 9000 km over 128 frames of 60 s: 300 km/s is 2 samples per frame; the rate
        // grid at wavenumber a is (512 x 9000 km) / (128 x 60 s) / a = 600 / a km/s, so only the
        // lowest wavenumber of a narrow feature is unresolvable against 0 and 600.
        int nR = 512, nPhi = 8, nT = 128;
        double dt = 60, drKm = 9000;
        double v = 300 * dt / drKm;
        float[][] outward = component(nR, nT, (r, t) -> gauss(r - (40 + v * t), 2.5));
        float[][] inward = component(nR, nT, (r, t) -> gauss(r - (470 - v * t), 2.5));
        float[][] ring = component(nR, nT, (r, t) -> 0.7 * gauss(r - 380, 6));

        // PASS 200..400 outward keeps the outward feature only
        PolarCube cube = cube(nR, nPhi, nT, outward, inward, ring);
        float[][] mean = cube.finish();
        expect("the static ring lives in the time mean", Math.abs(mean[0][380] - 0.7) < 0.05); // the inward transit adds a little
        FourierParams pass = new FourierParams(FourierParams.Kind.RADIAL, FourierParams.Mode.PASS, 200, 400, FourierParams.Direction.POSITIVE, 1, nR, 16);
        FourierFilter.Spectrum sp = FourierFilter.filterCube(cube, pass, drKm, dt);
        double keep = projection(cube, outward, nR, nT), leak = leakage(cube, outward, nR, nT, inward);
        expect(String.format("PASS keeps the outward feature (%.0f percent)", 100 * keep), keep > 0.9 && keep < 1.1);
        expect(String.format("PASS removes the inward feature (%.1f percent leak)", 100 * leak), leak < 0.1); // includes the kept feature's own unresolvable lowest wavenumber
        int peak = argmax(sp.powerPositive());
        expect(String.format("spectrum peaks near +300 km/s (%.0f)", sp.rate()[peak]), sp.rate()[peak] > 255 && sp.rate()[peak] < 345);
        int peakNeg = argmax(sp.powerNegative());
        expect(String.format("negative spectrum peaks near 300 km/s too (%.0f)", sp.rate()[peakNeg]), sp.rate()[peakNeg] > 255 && sp.rate()[peakNeg] < 345);

        // NOTCH 200..400 outward removes the outward feature and keeps the inward one
        cube = cube(nR, nPhi, nT, outward, inward, ring);
        cube.finish();
        FourierParams notch = new FourierParams(FourierParams.Kind.RADIAL, FourierParams.Mode.NOTCH, 200, 400, FourierParams.Direction.POSITIVE, 1, nR, 16);
        FourierFilter.filterCube(cube, notch, drKm, dt);
        double keepIn = projection(cube, inward, nR, nT), leakOut = projection(cube, outward, nR, nT);
        expect(String.format("NOTCH keeps the inward feature (%.0f percent)", 100 * keepIn), keepIn > 0.9 && keepIn < 1.1);
        expect(String.format("NOTCH removes the outward feature (%.1f percent left)", 100 * leakOut), leakOut < 0.1);

        // direction: NEGATIVE pass keeps the inward feature instead
        cube = cube(nR, nPhi, nT, outward, inward, ring);
        cube.finish();
        FourierParams passIn = new FourierParams(FourierParams.Kind.RADIAL, FourierParams.Mode.PASS, 200, 400, FourierParams.Direction.NEGATIVE, 1, nR, 16);
        FourierFilter.filterCube(cube, passIn, drKm, dt);
        expect("PASS inward keeps the inward feature, not the outward one",
                projection(cube, inward, nR, nT) > 0.9 && projection(cube, outward, nR, nT) < 0.1);
    }

    private static void angular() {
        // 128 frames of 4 min; the pattern turns once per 96 min, m = 3, so its power sits at an
        // exact bin; the time window smears it by two bins, which a band of 25 percent covers.
        int nR = 4, nPhi = 256, nT = 128;
        double dt = 240;
        double omega0 = 2 * Math.PI / (96 * 60);
        double dPhi = 2 * Math.PI / nPhi;
        float[][] rotating = component(nPhi, nT, (p, t) -> Math.cos(3 * (p * dPhi - omega0 * t * dt)));
        float[][] fixed = component(nPhi, nT, (p, t) -> 0.8 * Math.cos(4 * p * dPhi));

        PolarCube cube = angularCube(nR, nPhi, nT, rotating, fixed); // ANGULAR: slices are radii, inner is phi
        FourierParams notch = new FourierParams(FourierParams.Kind.ANGULAR, FourierParams.Mode.NOTCH, omega0 * 0.75, omega0 * 1.25, FourierParams.Direction.BOTH, 1, 1024, nPhi);
        FourierFilter.Spectrum sp = FourierFilter.filterCube(cube, notch, 1, dt);
        double leak = projection(cube, rotating, nPhi, nT), keep = projection(cube, fixed, nPhi, nT);
        expect(String.format("orbital NOTCH removes the rotating pattern (%.1f percent left)", 100 * leak), leak < 0.05);
        expect(String.format("orbital NOTCH keeps the static m = 4 pattern (%.0f percent)", 100 * keep), keep > 0.95 && keep < 1.05);
        int peak = argmax(sp.powerPositive());
        double period = 2 * Math.PI / sp.rate()[peak] / 60;
        expect(String.format("prograde spectrum peaks at the orbital period (%.1f min)", period), period > 82 && period < 112);

        // the pattern rotates prograde: a retrograde-only notch must leave it alone
        cube = angularCube(nR, nPhi, nT, rotating, fixed);
        FourierParams retro = new FourierParams(FourierParams.Kind.ANGULAR, FourierParams.Mode.NOTCH, omega0 * 0.75, omega0 * 1.25, FourierParams.Direction.NEGATIVE, 1, 1024, nPhi);
        FourierFilter.filterCube(cube, retro, 1, dt);
        expect("a retrograde-only notch leaves the prograde pattern in place", projection(cube, rotating, nPhi, nT) > 0.9);
    }

    interface Field {
        double at(int inner, int t);
    }

    private static float[][] component(int nInner, int nT, Field f) {
        float[][] c = new float[nT][nInner];
        for (int t = 0; t < nT; t++)
            for (int i = 0; i < nInner; i++)
                c[t][i] = (float) f.at(i, t);
        return c;
    }

    private static double gauss(double x, double sigma) {
        return Math.exp(-x * x / (2 * sigma * sigma));
    }

    private static PolarCube cube(int nR, int nPhi, int nT, float[][]... parts) {
        PolarCube cube = new PolarCube(FourierParams.Kind.RADIAL, nR, nPhi, nT, 2, 0.01);
        fill(cube, nR, nT, parts);
        return cube;
    }

    private static PolarCube angularCube(int nR, int nPhi, int nT, float[][]... parts) {
        PolarCube cube = new PolarCube(FourierParams.Kind.ANGULAR, nR, nPhi, nT, 2, 0.5);
        fill(cube, nPhi, nT, parts);
        return cube;
    }

    private static void fill(PolarCube cube, int nInner, int nT, float[][]... parts) {
        for (int s = 0; s < cube.nSlices; s++) {
            java.util.Arrays.fill(cube.valid[s], true);
            for (int t = 0; t < nT; t++)
                for (int i = 0; i < nInner; i++) {
                    float v = 0;
                    for (float[][] p : parts)
                        v += p[t][i];
                    cube.data[s][t * nInner + i] = v;
                }
        }
    }

    // Interior only: the Tukey taper covers the outer eighth of each windowed axis.
    private static boolean interior(int i, int nInner, int t, int nT, boolean innerWindowed) {
        int tEdge = nT / 8, iEdge = innerWindowed ? nInner / 8 : 0;
        return t >= tEdge && t < nT - tEdge && i >= iEdge && i < nInner - iEdge;
    }

    // <cube, part> / <part, part> over the interior of slice 0: 1 when the part survived intact.
    private static double projection(PolarCube cube, float[][] part, int nInner, int nT) {
        boolean windowed = cube.kind == FourierParams.Kind.RADIAL;
        double num = 0, den = 0;
        float[] d = cube.data[0];
        for (int t = 0; t < nT; t++)
            for (int i = 0; i < nInner; i++) {
                if (!interior(i, nInner, t, nT, windowed))
                    continue;
                num += d[t * nInner + i] * part[t][i];
                den += part[t][i] * part[t][i];
            }
        return num / den;
    }

    // energy of (cube - kept) relative to the energy of the parts that should have gone
    private static double leakage(PolarCube cube, float[][] kept, int nInner, int nT, float[][]... removed) {
        boolean windowed = cube.kind == FourierParams.Kind.RADIAL;
        double num = 0, den = 0;
        float[] d = cube.data[0];
        for (int t = 0; t < nT; t++)
            for (int i = 0; i < nInner; i++) {
                if (!interior(i, nInner, t, nT, windowed))
                    continue;
                double r = d[t * nInner + i] - kept[t][i];
                num += r * r;
                for (float[][] p : removed)
                    den += p[t][i] * p[t][i];
            }
        return num / den;
    }

    private static int argmax(double[] a) {
        int best = 0;
        for (int i = 1; i < a.length; i++)
            if (a[i] > a[best])
                best = i;
        return best;
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private FourierFilterCheck() {}

}
