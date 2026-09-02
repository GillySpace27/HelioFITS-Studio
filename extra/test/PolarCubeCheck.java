package org.helioviewer.jhv.image.fourier;

import org.helioviewer.jhv.metadata.Region;

/**
 * A transposed or mirrored polar map looks perfectly fine on a symmetric image, and every
 * coronagraph movie is close enough to symmetric to hide it. So this samples an asymmetric
 * picture: a cos(4 phi) pattern with an occulter hole, a blob at the top of the buffer that must
 * land at phi = 0, a blob at the left that must land at phi = pi / 2 (the PolarBasis.angle
 * convention), and a NaN wedge that must come out invalid rather than interpolated across.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.image.fourier.PolarCubeCheck
 */
public final class PolarCubeCheck {

    private static int failures;

    public static void main(String[] args) {
        int w = 512, h = 512;
        double pix = 8. / w; // solar radii per pixel: the buffer spans -4 to +4
        Region sc = new Region(-4, -4, 8, 8);
        double rIn = 1.2, rOut = 3.6;
        int nR = 256, nPhi = 256, nT = 3; // dr is 0.6 pixel: two bilinear resamplings then cost under 2 percent

        // cos(4 phi) times a radial envelope, plus 2 so it is positive; hole inside r = 1; NaN wedge at phi in [pi, pi + 0.3]
        float[] img = new float[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                double dx = sc.llx + (x + .5) * pix, dy = sc.lly + (y + .5) * pix;
                double r = Math.hypot(dx, dy);
                double phi = Math.atan2(-dx, -dy);
                if (phi < 0)
                    phi += 2 * Math.PI;
                float v;
                if (r < 1 || (phi > Math.PI && phi < Math.PI + 0.3))
                    v = Float.NaN;
                else
                    v = (float) (2 + Math.exp(-(r - 2.4) * (r - 2.4)) * Math.cos(4 * phi));
                img[y * w + x] = v;
            }

        PolarCube cube = new PolarCube(FourierParams.Kind.RADIAL, nR, nPhi, nT, rIn, (rOut - rIn) / nR);
        for (int t = 0; t < nT; t++)
            cube.put(t, img, w, h, sc);
        float[][] mean = cube.finish();

        // the wedge is invalid in every radius, and its neighbours are valid
        int wedgeSlice = (int) ((Math.PI + 0.15) / cube.dPhi);
        int okSlice = (int) ((Math.PI / 2) / cube.dPhi);
        boolean wedgeInvalid = true, neighbourValid = true;
        for (int ir = 0; ir < nR; ir++) {
            wedgeInvalid &= !cube.valid[wedgeSlice][ir];
            neighbourValid &= cube.valid[okSlice][ir];
        }
        expect("NaN wedge is invalid in the cube, its neighbours valid", wedgeInvalid && neighbourValid);

        // round trip: three identical frames, so the mean plane carries the picture
        float[] back = new float[w * h];
        cube.toCartesian(back, w, h, sc, 1, true);
        double err = 0, ref = 0;
        int count = 0, nanInWedge = 0, wedgeCount = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                double dx = sc.llx + (x + .5) * pix, dy = sc.lly + (y + .5) * pix;
                double r = Math.hypot(dx, dy);
                if (r < rIn + 0.05 || r > rOut - 0.05)
                    continue;
                double phi = Math.atan2(-dx, -dy);
                if (phi < 0)
                    phi += 2 * Math.PI;
                int i = y * w + x;
                if (phi > Math.PI + 0.02 && phi < Math.PI + 0.28) {
                    wedgeCount++;
                    if (Float.isNaN(back[i]))
                        nanInWedge++;
                    continue;
                }
                if (Float.isNaN(img[i]) || Float.isNaN(back[i]))
                    continue;
                double d = back[i] - img[i], a = img[i] - 2;
                err += d * d;
                ref += a * a;
                count++;
            }
        double rms = Math.sqrt(err / count) / Math.sqrt(ref / count);
        expect(String.format("forward and back within 2 percent of the pattern amplitude (%.2f percent)", 100 * rms), rms < 0.02);
        expect("the wedge comes back NaN, not interpolated across", nanInWedge > 0.9 * wedgeCount);

        // orientation: a blob at the top of the buffer (r = 2.4) lands at phi = 0; one at the left (r = 3.0) at phi = pi / 2
        float[] blobs = new float[w * h];
        int cx = w / 2, cyTop = (int) ((-2.4 - sc.lly) / pix), cxLeft = (int) ((-3.0 - sc.llx) / pix), cy = h / 2;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                double d1 = Math.hypot(x - cx, y - cyTop), d2 = Math.hypot(x - cxLeft, y - cy);
                blobs[y * w + x] = (float) (Math.exp(-d1 * d1 / 50) + 2 * Math.exp(-d2 * d2 / 50));
            }
        PolarCube c2 = new PolarCube(FourierParams.Kind.RADIAL, nR, nPhi, 2, rIn, (rOut - rIn) / nR);
        c2.put(0, blobs, w, h, sc);
        c2.put(1, blobs, w, h, sc);
        float[][] m2 = c2.finish();
        int irTop = (int) ((2.4 - rIn) / c2.dr), irLeft = (int) ((3.0 - rIn) / c2.dr);
        int topSlice = argmaxSlice(m2, irTop, 0.3, 1.5), leftSlice = argmaxSlice(m2, irLeft, 0.3, 2.5);
        expect("top blob at phi = 0 (slice " + topSlice + ")", Math.min(topSlice, nPhi - topSlice) <= 1);
        expect("left blob at phi = pi/2 (slice " + leftSlice + ", expected " + nPhi / 4 + ")", Math.abs(leftSlice - nPhi / 4) <= 1);

        // time varies: without the mean the cube holds the fluctuation
        PolarCube c3 = new PolarCube(FourierParams.Kind.ANGULAR, 16, 64, 4, rIn, (rOut - rIn) / 16);
        for (int t = 0; t < 4; t++) {
            float[] f = new float[w * h];
            for (int i = 0; i < f.length; i++)
                f[i] = Float.isNaN(img[i]) ? Float.NaN : img[i] * (1 + 0.1f * t);
            c3.put(t, f, w, h, sc);
        }
        c3.finish();
        float[] fluct = new float[w * h];
        c3.toCartesian(fluct, w, h, sc, 0, false);
        int probe = (h / 2 + (int) (2.4 / pix)) * w + w / 2; // bottom, phi = pi... use a valid spot: phi = 3pi/2 is right
        probe = h / 2 * w + (w / 2 + (int) (2.4 / pix));
        double expected = -0.15 * img[probe]; // mean factor 1.15, frame 0 factor 1
        expect(String.format("fluctuation at t = 0 is -0.15 x image (%.3f vs %.3f)", fluct[probe], expected), Math.abs(fluct[probe] - expected) < 0.02 * Math.abs(expected) + 1e-3);

        System.out.println(failures == 0 ? "PolarCubeCheck: PASS" : "PolarCubeCheck: " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    // slice with the largest mean at radius ir among values in (lo, hi]
    private static int argmaxSlice(float[][] mean, int ir, double lo, double hi) {
        int best = -1;
        double bestV = -1;
        for (int s = 0; s < mean.length; s++) {
            double v = mean[s][ir];
            if (v > lo && v <= hi && v > bestV) {
                bestV = v;
                best = s;
            }
        }
        return best;
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    private PolarCubeCheck() {}

}
