package org.helioviewer.jhv.image.fourier;

import org.helioviewer.jhv.metadata.Region;

/**
 * A movie resampled onto (r, phi, t): radius in solar radii from rIn in steps of dr, position
 * angle phi in nPhi steps around the circle, time as uniform sample index. Stored slice-major so
 * one slice is the 2D plane a velocity filter transforms: for RADIAL a slice is one phi and holds
 * (t, r); for ANGULAR a slice is one r and holds (t, phi). The inner index varies fastest.
 *
 * <p>Geometry, per buffer pixel (x, y) with y the row from the top: dx = llx + (x + .5) pixX and
 * dy = lly + (y + .5) pixY in the Sun-centred region, r = hypot(dx, dy), phi = atan2(-dx, -dy):
 * 0 at the top of the buffer, pi/2 at the left, increasing anticlockwise on screen, the same as
 * PolarBasis.angle. So dx = -r sin(phi) and dy = -r cos(phi).
 *
 * <p>Float arrays only, no LWJGL, so its check runs with -cp bin. Missing input is NaN; a cell
 * is valid after finish() when more than half its samples were present.
 */
public final class PolarCube {

    public final FourierParams.Kind kind;
    public final int nR, nPhi, nT;
    public final double rIn, dr, dPhi;
    final int nSlices, nInner;
    final float[][] data;      // [slice][t * nInner + inner]
    final boolean[][] valid;   // [slice][inner]
    private final float[][] mean; // [slice][inner], filled by finish()

    public PolarCube(FourierParams.Kind _kind, int _nR, int _nPhi, int _nT, double _rIn, double _dr) {
        kind = _kind;
        nR = _nR;
        nPhi = _nPhi;
        nT = _nT;
        rIn = _rIn;
        dr = _dr;
        dPhi = 2 * Math.PI / nPhi;
        nSlices = kind == FourierParams.Kind.RADIAL ? nPhi : nR;
        nInner = kind == FourierParams.Kind.RADIAL ? nR : nPhi;
        data = new float[nSlices][nT * nInner];
        valid = new boolean[nSlices][nInner];
        mean = new float[nSlices][nInner];
    }

    public long bytes() {
        return 4L * nSlices * nT * nInner;
    }

    private int slice(int ir, int iphi) {
        return kind == FourierParams.Kind.RADIAL ? iphi : ir;
    }

    private int inner(int ir, int iphi) {
        return kind == FourierParams.Kind.RADIAL ? ir : iphi;
    }

    /** Sample uniform time index it from a frame in physical units (NaN = missing). */
    public void put(int it, float[] physical, int w, int h, Region sunCentred) {
        double pixX = sunCentred.width / w, pixY = sunCentred.height / h;
        for (int iphi = 0; iphi < nPhi; iphi++) {
            double phi = (iphi + .5) * dPhi;
            double sin = Math.sin(phi), cos = Math.cos(phi);
            for (int ir = 0; ir < nR; ir++) {
                double r = rIn + (ir + .5) * dr;
                double dx = -r * sin, dy = -r * cos;
                double px = (dx - sunCentred.llx) / pixX - .5;
                double py = (dy - sunCentred.lly) / pixY - .5;
                data[slice(ir, iphi)][it * nInner + inner(ir, iphi)] = bilinear(physical, w, h, px, py);
            }
        }
    }

    // Bilinear with NaN neighbours dropped and the weights renormalised; NaN when none remain.
    static float bilinear(float[] img, int w, int h, double px, double py) {
        int x0 = (int) Math.floor(px), y0 = (int) Math.floor(py);
        double fx = px - x0, fy = py - y0;
        double sum = 0, wsum = 0;
        for (int j = 0; j < 2; j++) {
            int y = y0 + j;
            if (y < 0 || y >= h)
                continue;
            double wy = j == 0 ? 1 - fy : fy;
            for (int i = 0; i < 2; i++) {
                int x = x0 + i;
                if (x < 0 || x >= w)
                    continue;
                float v = img[y * w + x];
                if (Float.isNaN(v))
                    continue;
                double wt = wy * (i == 0 ? 1 - fx : fx);
                sum += wt * v;
                wsum += wt;
            }
        }
        return wsum > 0 ? (float) (sum / wsum) : Float.NaN;
    }

    /** Subtract the per-cell time mean, mark validity, replace missing samples by 0. Returns the mean per [slice][inner]. */
    public float[][] finish() {
        for (int s = 0; s < nSlices; s++) {
            float[] d = data[s];
            for (int j = 0; j < nInner; j++) {
                double sum = 0;
                int count = 0;
                for (int t = 0; t < nT; t++) {
                    float v = d[t * nInner + j];
                    if (!Float.isNaN(v)) {
                        sum += v;
                        count++;
                    }
                }
                boolean ok = count > nT / 2;
                valid[s][j] = ok;
                float m = ok ? (float) (sum / count) : 0;
                mean[s][j] = m;
                for (int t = 0; t < nT; t++) {
                    int idx = t * nInner + j;
                    float v = d[idx];
                    d[idx] = ok && !Float.isNaN(v) ? v - m : 0;
                }
            }
        }
        return mean;
    }

    // Value at integer time it and fractional polar coordinates, bilinear in (r, phi) with phi wrapping; NaN when invalid.
    private float sample(int it, double ir, double iphi) {
        int r0 = (int) Math.floor(ir), p0 = (int) Math.floor(iphi);
        double fr = ir - r0, fp = iphi - p0;
        double sum = 0, wsum = 0;
        for (int j = 0; j < 2; j++) {
            int r = r0 + j;
            if (r < 0 || r >= nR)
                continue;
            double wr = j == 0 ? 1 - fr : fr;
            for (int i = 0; i < 2; i++) {
                int p = Math.floorMod(p0 + i, nPhi);
                int s = slice(r, p), in = inner(r, p);
                if (!valid[s][in])
                    continue;
                double wt = wr * (i == 0 ? 1 - fp : fp);
                sum += wt * data[s][it * nInner + in];
                wsum += wt;
            }
        }
        return wsum > 0 ? (float) (sum / wsum) : Float.NaN;
    }

    // Bilinear like sample(): a nearest-neighbour mean under a bilinear fluctuation ripples at the grid.
    private float meanAt(double ir, double iphi) {
        int r0 = (int) Math.floor(ir), p0 = (int) Math.floor(iphi);
        double fr = ir - r0, fp = iphi - p0;
        double sum = 0, wsum = 0;
        for (int j = 0; j < 2; j++) {
            int r = r0 + j;
            if (r < 0 || r >= nR)
                continue;
            double wr = j == 0 ? 1 - fr : fr;
            for (int i = 0; i < 2; i++) {
                int p = Math.floorMod(p0 + i, nPhi);
                int s = slice(r, p), in = inner(r, p);
                if (!valid[s][in])
                    continue;
                double wt = wr * (i == 0 ? 1 - fp : fp);
                sum += wt * mean[s][in];
                wsum += wt;
            }
        }
        return wsum > 0 ? (float) (sum / wsum) : Float.NaN;
    }

    /**
     * The cube at fractional time index u back on a pixel grid, NaN outside [rIn, rIn + nR dr] or
     * where the cube is invalid; addMean puts the subtracted time mean back (a NOTCH output).
     */
    public void toCartesian(float[] out, int w, int h, Region sunCentred, double u, boolean addMean) {
        int t0 = Math.clamp((int) Math.floor(u), 0, nT - 1);
        int t1 = Math.min(t0 + 1, nT - 1);
        double ft = Math.clamp(u - t0, 0, 1);
        double pixX = sunCentred.width / w, pixY = sunCentred.height / h;
        double rOut = rIn + nR * dr;
        for (int y = 0; y < h; y++) {
            double dy = sunCentred.lly + (y + .5) * pixY;
            for (int x = 0; x < w; x++) {
                double dx = sunCentred.llx + (x + .5) * pixX;
                double r = Math.hypot(dx, dy);
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
                float a = sample(t0, ir, iphi);
                float b = t1 == t0 ? a : sample(t1, ir, iphi);
                float v = Float.isNaN(a) || Float.isNaN(b) ? Float.NaN : (float) ((1 - ft) * a + ft * b);
                if (addMean && !Float.isNaN(v))
                    v += meanAt(ir, iphi);
                out[idx] = v;
            }
        }
    }

}
