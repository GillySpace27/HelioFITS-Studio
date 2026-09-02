package org.helioviewer.jhv.image.fourier;

import java.util.Arrays;
import java.util.Random;

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
 * <p>One step the paper does not spell out, because its examples were unsharp-masked (AIA) or
 * zero-mean (HMI): a raw coronagraph or EUV frame has a mean and a gradient far above the
 * noise, and the Hanning window leaks them into the lowest Fourier components of every
 * neighbourhood. Estimated across neighbourhoods those components look like enormous "noise",
 * the gate closes them wherever the local level is below three times the typical one, and the
 * picture falls apart. So each frame's smooth background (a box mean of side 2n + 1) bypasses
 * the gate: the fluctuation about it is gated, and the background is added back. The shot-noise
 * norm still uses the raw values.
 *
 * <p>nt is n for a movie, 8 for a short one, 1 for a single image (the paper's 2D case, Section
 * 3.3). Tiles are padded by mirror reflection by n in x and y and by nt in t (see pad), so every
 * interior point is covered by a full set of overlapping windows. Float arrays only; no LWJGL,
 * so the check runs with -cp bin.
 */
public final class NoiseGate {

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
     * Pads a W x H x D volume by px in x, py in y and pt in t by mirror reflection. NaN entries
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
     * neighbourhoods into a bounded reservoir per Fourier component, then takes a percentile.
     */
    public static final class Estimator {
        private final int components, capacity;
        private final float[][] reservoir;
        private final int[] count;
        private final long[] seen;
        private final Random random = new Random(12345);
        private final boolean shot;

        public Estimator(Setup setup, boolean shotModel, int reservoirPerComponent) {
            components = setup.components();
            capacity = reservoirPerComponent;
            reservoir = new float[components][capacity];
            count = new int[components];
            seen = new long[components];
            shot = shotModel;
        }

        /** One neighbourhood's amplitudes (already transformed) with its shot norm. */
        synchronized void add(float[] re, float[] im, double sqrtSum) {
            double norm = shot ? (sqrtSum > 0 ? 1 / sqrtSum : 0) : 1;
            if (norm == 0)
                return;
            for (int k = 0; k < components; k++) {
                float amp = (float) (Math.sqrt(re[k] * (double) re[k] + im[k] * (double) im[k]) * norm);
                long s = seen[k]++;
                if (count[k] < capacity) {
                    reservoir[k][count[k]++] = amp;
                } else {
                    int slot = (int) (random.nextDouble() * (s + 1));
                    if (slot < capacity)
                        reservoir[k][slot] = amp;
                }
            }
        }

        /** The noise spectrum: beta(k) for shot, |N'_a(k)| for additive. */
        public float[] noise(int percentile) {
            float[] out = new float[components];
            for (int k = 0; k < components; k++) {
                int c = count[k];
                if (c == 0)
                    continue;
                float[] r = Arrays.copyOf(reservoir[k], c);
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
            // horizontal running sums into rows, then vertical
            for (int y = 0; y < H; y++) {
                double sum = 0;
                int count = 0;
                int row = y * W;
                for (int x = -r; x <= r; x++) {
                    int cx = Math.clamp(x, 0, W - 1);
                    sum += vol[base + row + cx];
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

    /** Pass 1 over a padded fluctuation volume: every neighbourhood on a stride-n lattice feeds the estimator. */
    public static void estimate(float[] fluct, float[] raw, int W, int H, int D, Setup s, Estimator est) {
        int n = s.n(), nt = s.nt();
        float[] re = new float[s.components()], im = new float[s.components()];
        for (int z0 = 0; z0 + nt <= D; z0 += nt)
            for (int y0 = 0; y0 + n <= H; y0 += n)
                for (int x0 = 0; x0 + n <= W; x0 += n) {
                    double sqrtSum = extract(fluct, raw, W, H, x0, y0, z0, s, re, im);
                    transform(re, im, s, false);
                    est.add(re, im, sqrtSum);
                }
    }

    /** A tile's frames (unpadded, NaN allowed), padded and split into background and fluctuation. */
    public record Padded(float[] raw, float[] background, float[] fluctuation, int W, int H, int D, int pt) {}

    public static Padded prepare(float[] vol, int w, int h, int d, Setup s) {
        int n = s.n(), pt = s.nt() > 1 ? s.nt() : 0;
        int W = w + 2 * n, H = h + 2 * n, D = d + 2 * pt;
        float[] raw = pad(vol, w, h, d, n, n, pt);
        float[] bg = background(raw, W, H, D, n);
        float[] fluct = new float[raw.length];
        for (int i = 0; i < raw.length; i++)
            fluct[i] = raw[i] - bg[i];
        return new Padded(raw, bg, fluct, W, H, D, pt);
    }

    /** Pass 1 for one tile. */
    public static void estimateTile(float[] vol, int w, int h, int d, Setup s, Estimator est) {
        Padded p = prepare(vol, w, h, d, s);
        estimate(p.fluctuation(), p.raw(), p.W(), p.H(), p.D(), s, est);
    }

    /** Pass 2 for one tile: the gated field, unpadded, background restored, NaN where the input was. */
    public static float[] gateTile(float[] vol, int w, int h, int d, Setup s, float[] noise, NoiseGateParams params) {
        Padded p = prepare(vol, w, h, d, s);
        float[] gated = gateVolume(p.fluctuation(), p.raw(), p.W(), p.H(), p.D(), s, noise, params);
        float[] out = new float[vol.length];
        int n = s.n(), pt = p.pt(), W = p.W(), H = p.H();
        for (int z = 0; z < d; z++)
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int i = (z * h + y) * w + x, j = ((z + pt) * H + y + n) * W + x + n;
                    out[i] = Float.isNaN(vol[i]) ? Float.NaN : gated[j] + p.background()[j];
                }
        return out;
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

    /**
     * Pass 2: gates every neighbourhood of a padded volume on the n/4 stagger and returns the
     * overlap-added, doubly windowed, normalised result (same padded size; the caller crops).
     * Neighbourhood rows are processed in parallel within each of the four y-phases of the
     * stagger, so no two concurrent windows overlap in y.
     */
    public static float[] gateVolume(float[] fluct, float[] raw, int W, int H, int D, Setup s, float[] noise, NoiseGateParams p) {
        int n = s.n(), nt = s.nt(), stride = s.stride(), strideT = s.strideT();
        float[] out = new float[fluct.length];
        boolean wiener = p.gate() == NoiseGateParams.Gate.WIENER;
        boolean shot = p.model() == NoiseGateParams.Model.SHOT;
        double gamma = p.gamma();
        int rowsPerPhase = (H - n) / n + 1; // y starts y0 = phase * stride + row * n
        for (int phase = 0; phase < 4; phase++) {
            int ph = phase;
            ParallelRange.run(rowsPerPhase, (from, to) -> {
                float[] re = new float[s.components()], im = new float[s.components()];
                for (int row = from; row < to; row++) {
                    int y0 = ph * stride + row * n;
                    if (y0 + n > H)
                        continue;
                    if (Thread.currentThread().isInterrupted())
                        return;
                    for (int z0 = 0; z0 + nt <= D; z0 += strideT)
                        for (int x0 = 0; x0 + n <= W; x0 += stride) {
                            double sqrtSum = extract(fluct, raw, W, H, x0, y0, z0, s, re, im);
                            transform(re, im, s, false);
                            gate(re, im, noise, gamma * (shot ? sqrtSum : 1), wiener);
                            transform(re, im, s, true);
                            for (int t = 0; t < nt; t++)
                                for (int y = 0; y < n; y++) {
                                    int dst = ((z0 + t) * H + y0 + y) * W + x0, src = (t * n + y) * n;
                                    for (int x = 0; x < n; x++)
                                        out[dst + x] += re[src + x] * s.window()[src + x];
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
