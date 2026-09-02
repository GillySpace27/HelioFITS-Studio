package org.helioviewer.jhv.image.fourier;

/**
 * Radix-2 complex FFT on split float arrays, in place, for the sequence filters. About a hundred
 * lines because nothing in the tree or in lib/ transforms anything, and the filters need only
 * power-of-two lengths.
 *
 * <p>Convention: forward is X[k] = sum_j x[j] exp(-2 pi i j k / n), inverse divides by n, so a
 * pattern f(r - v t) puts its power on omega = -v k (see FourierFilter). Twiddle tables are cached
 * per length; the strided transforms copy each line through a scratch buffer, which keeps the
 * inner loop contiguous and the code to one transform.
 *
 * <p>Checked by extra/test/FFTCheck.java: impulse, a single-bin exponential (pins the sign),
 * Parseval, round trips in 1D, 2D and 3D, and the sign of the slope of a moving feature.
 */
public final class FFT {

    // Twiddles by log2(n): the noise gate transforms a 16-point line a hundred million times per
    // movie, and a hash lookup per line was a measurable share of that.
    private static final float[][][] twiddles = new float[32][][];

    private static float[][] twiddle(int n) {
        int p = Integer.numberOfTrailingZeros(n);
        float[][] t = twiddles[p];
        if (t == null) {
            float[] cos = new float[n / 2], sin = new float[n / 2];
            for (int i = 0; i < n / 2; i++) {
                double a = -2 * Math.PI * i / n;
                cos[i] = (float) Math.cos(a);
                sin[i] = (float) Math.sin(a);
            }
            t = new float[][]{cos, sin};
            twiddles[p] = t; // a benign race: two threads compute the same table
        }
        return t;
    }

    /** In place over the first n entries; n a power of two. */
    public static void transform(float[] re, float[] im, int n, boolean inverse) {
        if (n < 2)
            return;
        if ((n & (n - 1)) != 0)
            throw new IllegalArgumentException("FFT length must be a power of two: " + n);
        // bit reversal
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1)
                j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        float[][] w = twiddle(n);
        float[] cos = w[0], sin = w[1];
        float sign = inverse ? -1 : 1;
        for (int len = 2; len <= n; len <<= 1) {
            int half = len >> 1, step = n / len;
            for (int i = 0; i < n; i += len) {
                for (int j = 0, k = 0; j < half; j++, k += step) {
                    float wr = cos[k], wi = sign * sin[k];
                    int a = i + j, b = a + half;
                    float xr = re[b] * wr - im[b] * wi;
                    float xi = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                }
            }
        }
        if (inverse) {
            float s = 1f / n;
            for (int i = 0; i < n; i++) {
                re[i] *= s;
                im[i] *= s;
            }
        }
    }

    /**
     * Transform along one axis of a packed array whose first dimension varies fastest
     * (dims[0] fastest, dims[dims.length - 1] slowest). Every line along that axis is transformed.
     */
    public static void transformAxis(float[] re, float[] im, int[] dims, int axis, boolean inverse) {
        int n = dims[axis];
        int stride = 1;
        for (int d = 0; d < axis; d++)
            stride *= dims[d];
        int total = 1;
        for (int d : dims)
            total *= d;
        int block = stride * n; // one full span along the axis, repeated total / block times
        float[] lr = new float[n], li = new float[n];
        for (int base = 0; base < total; base += block) {
            for (int off = 0; off < stride; off++) {
                int start = base + off;
                for (int i = 0, p = start; i < n; i++, p += stride) {
                    lr[i] = re[p];
                    li[i] = im[p];
                }
                transform(lr, li, n, inverse);
                for (int i = 0, p = start; i < n; i++, p += stride) {
                    re[p] = lr[i];
                    im[p] = li[i];
                }
            }
        }
    }

    /** Packed [y][x], x fastest. */
    public static void transform2D(float[] re, float[] im, int nx, int ny, boolean inverse) {
        int[] dims = {nx, ny};
        transformAxis(re, im, dims, 0, inverse);
        transformAxis(re, im, dims, 1, inverse);
    }

    /** Packed [z][y][x], x fastest. */
    public static void transform3D(float[] re, float[] im, int nx, int ny, int nz, boolean inverse) {
        int[] dims = {nx, ny, nz};
        transformAxis(re, im, dims, 0, inverse);
        transformAxis(re, im, dims, 1, inverse);
        transformAxis(re, im, dims, 2, inverse);
    }

    /** Signed frequency index: 0, 1, ..., n/2 - 1, -n/2, ..., -1. */
    public static int signedIndex(int i, int n) {
        return i < n / 2 ? i : i - n;
    }

    public static int nextPowerOfTwo(int n) {
        int p = 1;
        while (p < n)
            p <<= 1;
        return p;
    }

    private FFT() {}

}
