package org.helioviewer.jhv.image.fourier;

import java.util.Arrays;
import java.util.Random;

import javax.annotation.Nullable;

import org.helioviewer.jhv.thread.ParallelRange;

/**
 * The 3D noise gate of DeForest 2017 (ApJ 838, 155, Section 2) on float volumes.
 *
 * <p>A volume is cut into n x n x nt neighbourhoods staggered every n/4 in each axis, each
 * apodized by the Hanning window of Eq. 15, w = sin^2((x + .5) pi/n) sin^2((y + .5) pi/n)
 * sin^2((t + .5) pi/nt), transformed, gated component by component against a threshold
 * gamma N'(k) (Eq. 12) with N' the noise spectrum estimated from the data (Eq. 7 for shot noise,
 * Eq. 8 for additive), transformed back, windowed again (Eq. 16), and summed. Because
 * sum_{j=0..3} sin^4(x + j pi/4) = 1.5 (Eq. 17) the sum is divided by 1.5 per windowed axis.
 * The gate is HARD (Eq. 10: zero below threshold, one above) or WIENER (Eq. 11).
 *
 * <p>Shot noise: |N'_s(k)| = beta(k) sum sqrt(Im) over the neighbourhood (Eq. 4), beta(k) a
 * percentile across neighbourhoods of |Im'(k)| / sum sqrt(Im) (Eq. 7; the median unless the
 * scene is highly structured). Additive: |N'_a(k)| a percentile of |Im'(k)| itself (Eq. 8).
 * The sum of square roots is over the raw values, as the paper writes it, clamped at zero for
 * data that can go negative.
 *
 * <p>Two things the paper leaves to the reader. Its examples were unsharp-masked (AIA) or
 * zero-mean (HMI); a raw coronagraph or EUV frame has a mean and a gradient far above the noise,
 * the Hanning window leaks them into the lowest Fourier components of every neighbourhood, the
 * estimate across neighbourhoods calls those components "noise", the gate closes them wherever
 * the local level is below three times the typical one, and the picture falls apart. So each
 * frame's smooth background (a box mean of side 2n + 1) bypasses the gate: the fluctuation about
 * it is gated and the background added back; the shot-noise norm still uses the raw values. And
 * the noise level of a coronagraph frame changes with radius, which the paper names as an
 * obvious extension: the estimate is kept per radial band and interpolated in radius, so the
 * threshold follows the noise without a step anywhere.
 *
 * <p>nt is n for a movie, 8 for a short one, 1 for a single image (the paper's 2D case, Section
 * 3.3). A tile handed in with a halo of real neighbouring pixels gates exactly as the whole
 * image would (the check compares the two); mirror reflection is for the image edge only.
 * Float arrays only; no LWJGL, so the check runs with -cp bin.
 */
public final class NoiseGate {

    private static final int MIN_BAND_SAMPLES = 32;

    /** Neighbourhood geometry and its window; dims is 3 for a movie, 2 for the per-frame case. */
    public record Setup(int n, int nt, float[] window) {
        public int dims() {
            return nt > 1 ? 3 : 2;
        }

        public int stride() {
            return n / 4;
        }

        public int strideT() {
            return nt > 1 ? nt / 4 : 1;
        }

        public int components() {
            return n * n * nt;
        }

        /** The overlap-add sum of the doubled window over the stagger, per Eq. 17. */
        public double overlapSum() {
            return Math.pow(1.5, dims());
        }
    }

    /**
     * Radial bands for the noise estimate: (cx, cy) the Sun's pixel position in the volume's own
     * coordinates, rMax the radius of the outermost band edge, bands the count. Null means one
     * level everywhere.
     */
    public record Radial(double cx, double cy, double rMax, int bands) {
        /** Fractional band index of a point, in [0, bands - 1]. */
        double at(double x, double y) {
            double r = Math.hypot(x - cx, y - cy) / rMax * bands;
            return Math.clamp(r, 0, bands - 1);
        }
    }

    public static Setup setup(int n, int frames) {
        int nt = frames >= 32 ? n : frames >= 16 ? 8 : 1;
        return new Setup(n, nt, window(n, nt));
    }

    static float[] window(int n, int nt) {
        float[] w = new float[n * n * nt];
        for (int t = 0; t < nt; t++) {
            double wt = nt > 1 ? sq(Math.sin((t + .5) * Math.PI / nt)) : 1;
            for (int y = 0; y < n; y++) {
                double wy = sq(Math.sin((y + .5) * Math.PI / n));
                for (int x = 0; x < n; x++)
                    w[(t * n + y) * n + x] = (float) (wt * wy * sq(Math.sin((x + .5) * Math.PI / n)));
            }
        }
        return w;
    }

    private static double sq(double v) {
        return v * v;
    }

    /** Index i reflected into [0, size): ..., 1, 0 | 0, 1, ..., size - 1 | size - 1, size - 2, ... */
    public static int reflect(int i, int size) {
        if (size == 1)
            return 0;
        int period = 2 * size;
        int m = Math.floorMod(i, period);
        return m < size ? m : period - 1 - m;
    }

    /**
     * Pads a w x h x d volume by px in x, py in y and pt in t by mirror reflection. NaN entries
     * (missing pixels) are replaced by 0: the gate needs numbers, and the caller restores them.
     */
    public static float[] pad(float[] vol, int w, int h, int d, int px, int py, int pt) {
        int W = w + 2 * px, H = h + 2 * py, D = d + 2 * pt;
        float[] out = new float[W * H * D];
        for (int z = 0; z < D; z++) {
            int sz = reflect(z - pt, d);
            for (int y = 0; y < H; y++) {
                int sy = reflect(y - py, h);
                int src = (sz * h + sy) * w, dst = (z * H + y) * W;
                for (int x = 0; x < W; x++) {
                    float v = vol[src + reflect(x - px, w)];
                    out[dst + x] = Float.isNaN(v) ? 0 : v;
                }
            }
        }
        return out;
    }

    /**
     * Gathers |Im'_i(k)| (normalised by sum sqrt(Im_i) for the shot model) from a sample of
     * neighbourhoods into a bounded reservoir per Fourier component and per radial band, plus one
     * for the whole image, then takes a percentile. A band with too few samples falls back to the
     * whole-image spectrum.
     */
    public static final class Estimator {
        private final int components, capacity, bands;
        private final float[][][] reservoir; // [band][component][sample]; band == bands is the whole image
        private final int[][] count;
        private final long[][] seen;
        private final Random random = new Random(12345);
        private final boolean shot;

        public Estimator(Setup setup, boolean shotModel, int reservoirPerComponent, int radialBands) {
            components = setup.components();
            capacity = reservoirPerComponent;
            bands = Math.max(0, radialBands);
            reservoir = new float[bands + 1][components][capacity];
            count = new int[bands + 1][components];
            seen = new long[bands + 1][components];
            shot = shotModel;
        }

        /** One neighbourhood's amplitudes (already transformed) with its shot norm; band < 0 for the whole image only. */
        synchronized void add(float[] re, float[] im, double sqrtSum, int band) {
            double norm = shot ? (sqrtSum > 0 ? 1 / sqrtSum : 0) : 1;
            if (norm == 0)
                return;
            for (int k = 0; k < components; k++) {
                float amp = (float) (Math.sqrt(re[k] * (double) re[k] + im[k] * (double) im[k]) * norm);
                put(bands, k, amp);
                if (band >= 0 && band < bands)
                    put(band, k, amp);
            }
        }

        private void put(int b, int k, float amp) {
            long s = seen[b][k]++;
            if (count[b][k] < capacity) {
                reservoir[b][k][count[b][k]++] = amp;
            } else {
                int slot = (int) (random.nextDouble() * (s + 1));
                if (slot < capacity)
                    reservoir[b][k][slot] = amp;
            }
        }

        /** The noise spectrum per band (beta(k) for shot, |N'_a(k)| for additive); index bands is the whole image. */
        public float[][] noise(int percentile) {
            float[][] out = new float[bands + 1][];
            out[bands] = percentileOf(bands, percentile);
            for (int b = 0; b < bands; b++)
                out[b] = count[b][0] >= MIN_BAND_SAMPLES ? percentileOf(b, percentile) : out[bands];
            return out;
        }

        private float[] percentileOf(int b, int percentile) {
            float[] out = new float[components];
            for (int k = 0; k < components; k++) {
                int c = count[b][k];
                if (c == 0)
                    continue;
                float[] r = Arrays.copyOf(reservoir[b][k], c);
                Arrays.sort(r);
                out[k] = r[Math.clamp((int) Math.round((c - 1) * percentile / 100.), 0, c - 1)];
            }
            return out;
        }
    }

    // Copies one neighbourhood of the fluctuation volume, windowed, into re (im cleared); returns the sum of square roots of the raw values there.
    private static double extract(float[] fluct, float[] raw, int W, int H, int x0, int y0, int z0, Setup s, float[] re, float[] im) {
        int n = s.n(), nt = s.nt();
        double sqrtSum = 0;
        for (int t = 0; t < nt; t++)
            for (int y = 0; y < n; y++) {
                int src = ((z0 + t) * H + y0 + y) * W + x0, dst = (t * n + y) * n;
                for (int x = 0; x < n; x++) {
                    float v = raw[src + x];
                    if (v > 0)
                        sqrtSum += Math.sqrt(v);
                    re[dst + x] = fluct[src + x] * s.window()[dst + x];
                }
            }
        Arrays.fill(im, 0);
        return sqrtSum;
    }

    /** Per frame, the box mean of side 2r + 1 over a padded volume (edges clamp), as the background the gate does not touch. */
    public static float[] background(float[] vol, int W, int H, int D, int r) {
        float[] bg = new float[vol.length];
        double[] rows = new double[H * W];
        for (int z = 0; z < D; z++) {
            int base = z * H * W;
            for (int y = 0; y < H; y++) {
                double sum = 0;
                int count = 0;
                int row = y * W;
                for (int x = -r; x <= r; x++) {
                    sum += vol[base + row + Math.clamp(x, 0, W - 1)];
                    count++;
                }
                for (int x = 0; x < W; x++) {
                    rows[row + x] = sum / count;
                    int out = Math.clamp(x - r, 0, W - 1), in = Math.clamp(x + r + 1, 0, W - 1);
                    sum += vol[base + row + in] - vol[base + row + out];
                }
            }
            for (int x = 0; x < W; x++) {
                double sum = 0;
                int count = 0;
                for (int y = -r; y <= r; y++) {
                    sum += rows[Math.clamp(y, 0, H - 1) * W + x];
                    count++;
                }
                for (int y = 0; y < H; y++) {
                    bg[base + y * W + x] = (float) (sum / count);
                    int out = Math.clamp(y - r, 0, H - 1), in = Math.clamp(y + r + 1, 0, H - 1);
                    sum += rows[in * W + x] - rows[out * W + x];
                }
            }
        }
        return bg;
    }

    private static void transform(float[] re, float[] im, Setup s, boolean inverse) {
        if (s.nt() > 1)
            FFT.transform3D(re, im, s.n(), s.n(), s.nt(), inverse);
        else
            FFT.transform2D(re, im, s.n(), s.n(), inverse);
    }

    /**
     * Pass 1 over a padded fluctuation volume: every neighbourhood on a stride-n lattice whose
     * centre lies inside the margin feeds the estimator. The margin keeps the mirror padding (a
     * fold is structure, not noise) and a tile's halo (which its neighbour also has) out of the
     * sample: each real neighbourhood is counted once, and only real ones.
     */
    public static void estimate(float[] fluct, float[] raw, int W, int H, int D, Setup s, Estimator est, @Nullable Radial radial, int margin) {
        int n = s.n(), nt = s.nt();
        float[] re = new float[s.components()], im = new float[s.components()];
        for (int z0 = 0; z0 + nt <= D; z0 += nt)
            for (int y0 = 0; y0 + n <= H; y0 += n) {
                double cy = y0 + n / 2.;
                if (cy < margin || cy >= H - margin)
                    continue;
                for (int x0 = 0; x0 + n <= W; x0 += n) {
                    double cx = x0 + n / 2.;
                    if (cx < margin || cx >= W - margin)
                        continue;
                    double sqrtSum = extract(fluct, raw, W, H, x0, y0, z0, s, re, im);
                    transform(re, im, s, false);
                    int band = radial == null ? -1 : (int) Math.round(radial.at(cx, cy));
                    est.add(re, im, sqrtSum, band);
                }
            }
    }

    /** The halo a tile needs: the neighbourhoods touching an interior pixel reach n beyond it, and their background box mean reaches n beyond that. */
    public static int halo(Setup s) {
        return 2 * s.n();
    }

    /**
     * A volume with its halo, split into background and fluctuation, padded in time. halo is the
     * width of real neighbouring pixels the caller already put around the tile (0 for a whole
     * image, which is mirror-padded by halo(s) here instead); crop is what to take off each side
     * of the result.
     */
    public record Padded(float[] raw, float[] background, float[] fluctuation, int W, int H, int D, int pt, int crop) {}

    public static Padded prepare(float[] vol, int w, int h, int d, Setup s, int halo) {
        int n = s.n(), pt = s.nt() > 1 ? s.nt() : 0;
        int px = halo > 0 ? 0 : halo(s);
        int W = w + 2 * px, H = h + 2 * px, D = d + 2 * pt;
        float[] raw = pad(vol, w, h, d, px, px, pt);
        float[] bg = background(raw, W, H, D, n);
        float[] fluct = new float[raw.length];
        for (int i = 0; i < raw.length; i++)
            fluct[i] = raw[i] - bg[i];
        return new Padded(raw, bg, fluct, W, H, D, pt, halo > 0 ? halo : px);
    }

    /** Pass 1 for one tile (w x h including its halo). radial is in the tile's own pixel coordinates. */
    public static void estimateTile(float[] vol, int w, int h, int d, Setup s, Estimator est, int halo, @Nullable Radial radial) {
        Padded p = prepare(vol, w, h, d, s, halo);
        estimate(p.fluctuation(), p.raw(), p.W(), p.H(), p.D(), s, est, shift(radial, p.crop() - halo), p.crop());
    }

    /** Pass 2 for one tile: the gated field without halo, background restored, NaN where the input was. */
    public static float[] gateTile(float[] vol, int w, int h, int d, Setup s, float[][] noise, NoiseGateParams params, int halo, @Nullable Radial radial) {
        Padded p = prepare(vol, w, h, d, s, halo);
        float[] gated = gateVolume(p.fluctuation(), p.raw(), p.W(), p.H(), p.D(), s, noise, params, shift(radial, p.crop() - halo));
        int crop = p.crop(), pt = p.pt(), W = p.W(), H = p.H();
        int ow = w - 2 * halo, oh = h - 2 * halo;
        float[] out = new float[ow * oh * d];
        for (int z = 0; z < d; z++)
            for (int y = 0; y < oh; y++)
                for (int x = 0; x < ow; x++) {
                    int i = (z * oh + y) * ow + x;
                    int src = (z * h + y + halo) * w + x + halo;              // in the caller's volume
                    int j = ((z + pt) * H + y + crop) * W + x + crop;          // in the padded volume
                    out[i] = Float.isNaN(vol[src]) ? Float.NaN : gated[j] + p.background()[j];
                }
        return out;
    }

    // The radial geometry moved by the padding the volume gained.
    @Nullable
    private static Radial shift(@Nullable Radial radial, int by) {
        return radial == null || by == 0 ? radial : new Radial(radial.cx() + by, radial.cy() + by, radial.rMax(), radial.bands());
    }

    // The gate of Eq. 10 or 11 against T(k) = gamma noise(k) scale, in place on the spectrum.
    static void gate(float[] re, float[] im, float[] noise, double gammaScale, boolean wiener) {
        for (int k = 0; k < re.length; k++) {
            double threshold = gammaScale * noise[k];
            if (!(threshold > 0))
                continue; // gamma 0, or a component the estimate never saw: open
            double amp = Math.sqrt(re[k] * (double) re[k] + im[k] * (double) im[k]);
            double f;
            if (wiener) {
                double ratio = amp / threshold;
                f = ratio / (1 + ratio);
            } else {
                f = amp < threshold ? 0 : 1;
            }
            re[k] *= (float) f;
            im[k] *= (float) f;
        }
    }

    // The threshold spectrum at a point: the band spectra interpolated in radius, or the whole-image one.
    private static void thresholdAt(float[][] noise, @Nullable Radial radial, double x, double y, float[] out) {
        if (radial == null || noise.length < 2) {
            System.arraycopy(noise[noise.length - 1], 0, out, 0, out.length);
            return;
        }
        double b = radial.at(x, y);
        int b0 = (int) Math.floor(b), b1 = Math.min(b0 + 1, radial.bands() - 1);
        float f = (float) (b - b0);
        float[] lo = noise[b0], hi = noise[b1];
        for (int k = 0; k < out.length; k++)
            out[k] = lo[k] + f * (hi[k] - lo[k]);
    }

    /**
     * Pass 2: gates every neighbourhood of a padded volume on the n/4 stagger and returns the
     * overlap-added, doubly windowed, normalised result (same padded size; the caller crops).
     * Neighbourhood rows are processed in parallel within each of the four y-phases of the
     * stagger, so no two concurrent windows overlap in y.
     */
    public static float[] gateVolume(float[] fluct, float[] raw, int W, int H, int D, Setup s, float[][] noise, NoiseGateParams p, @Nullable Radial radial) {
        int n = s.n(), nt = s.nt(), stride = s.stride(), strideT = s.strideT();
        float[] out = new float[fluct.length];
        boolean wiener = p.gate() == NoiseGateParams.Gate.WIENER;
        boolean shot = p.model() == NoiseGateParams.Model.SHOT;
        double gamma = p.gamma();
        int rowsPerPhase = (H - n) / n + 1; // y starts y0 = phase * stride + row * n
        for (int phase = 0; phase < 4; phase++) {
            int ph = phase;
            ParallelRange.run(rowsPerPhase, (from, to) -> {
                float[] re = new float[s.components()], im = new float[s.components()], thr = new float[s.components()];
                for (int row = from; row < to; row++) {
                    int y0 = ph * stride + row * n;
                    if (y0 + n > H)
                        continue;
                    if (Thread.currentThread().isInterrupted())
                        return;
                    for (int x0 = 0; x0 + n <= W; x0 += stride) {
                        thresholdAt(noise, radial, x0 + n / 2., y0 + n / 2., thr);
                        for (int z0 = 0; z0 + nt <= D; z0 += strideT) {
                            double sqrtSum = extract(fluct, raw, W, H, x0, y0, z0, s, re, im);
                            transform(re, im, s, false);
                            gate(re, im, thr, gamma * (shot ? sqrtSum : 1), wiener);
                            transform(re, im, s, true);
                            for (int t = 0; t < nt; t++)
                                for (int y = 0; y < n; y++) {
                                    int dst = ((z0 + t) * H + y0 + y) * W + x0, src = (t * n + y) * n;
                                    for (int x = 0; x < n; x++)
                                        out[dst + x] += re[src + x] * s.window()[src + x];
                                }
                        }
                    }
                }
            });
        }
        float norm = (float) (1 / s.overlapSum());
        for (int i = 0; i < out.length; i++)
            out[i] *= norm;
        return out;
    }

    private NoiseGate() {}

}
