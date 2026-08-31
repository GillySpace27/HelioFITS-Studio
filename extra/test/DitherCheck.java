package org.helioviewer.jhv.display;

import java.util.Random;

/**
 * What the dither does, and the much larger thing it cannot do.
 *
 * <p>This exists because the obvious next idea is wrong and looks right. Faced with banding on a
 * stretched 8-bit layer, the natural move is to add the noise earlier, at the texture fetch, sized
 * to the SOURCE's quantization so the transfer function carries it. It was written, and then
 * measured, and it recovers nothing: adding zero-mean noise to an already-quantized sample leaves
 * the local mean on the staircase, because the sub-bin detail was destroyed by the source
 * quantizer and no later stage knows where in its bin a sample came from. All it buys is grain of
 * the same amplitude as the terrace it was meant to hide. The numbers are below so the next person
 * to have the idea can see the result without rebuilding it.
 *
 * <p>What the dither in solarCommon.frag DOES do is the one case where the information still
 * exists: the final quantization to an 8-bit framebuffer, applied to a value that is still
 * continuous at that point. There the noise decorrelates the rounding error and the local mean
 * moves onto the true ramp, which is measured here too.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.display.DitherCheck
 */
public final class DitherCheck {

    private static final int N = 200_000;
    private static final int WINDOW = 512; // what the eye integrates over
    private static int failures;

    public static void main(String[] args) {
        screenDitherHelps();
        sourceDitherCannotRecover();

        if (failures != 0)
            throw new AssertionError(failures + " dither failure(s)");
        System.out.println("DitherCheck: PASS");
    }

    /**
     * The real case: a continuous value about to be rounded to 8 bits. Dither must move the local
     * mean onto the true ramp, which is the entire justification for adding noise to a picture.
     */
    private static void screenDitherHelps() {
        double[] truth = ramp(0.30, 0.30 + 6 / 255., 1);
        double[] plain = new double[N];
        double[] dithered = new double[N];
        Random random = new Random(11);
        for (int i = 0; i < N; i++) {
            plain[i] = Math.round(truth[i] * 255) / 255.;
            // solarCommon.frag's dither(): uniform over +/- one screen step.
            dithered[i] = Math.round(Math.clamp(truth[i] + (2 * random.nextDouble() - 1) / 255, 0, 1) * 255) / 255.;
        }
        double plainError = meanLocalError(plain, truth);
        double ditheredError = meanLocalError(dithered, truth);
        System.out.println("  screen quantization: local-mean error " + fmt(plainError)
                + " levels undithered, " + fmt(ditheredError) + " dithered");
        expect(ditheredError < 0.5 * plainError,
                "dithering the screen quantization must at least halve the local-mean error; got "
                        + fmt(ditheredError) + " against " + fmt(plainError) + " screen levels");
    }

    /**
     * The case that looks the same and is not: the value arrives already quantized, and is then
     * stretched. Every strength of dither leaves the same error, because the error is not the
     * rounding, it is the information the source threw away.
     */
    private static void sourceDitherCannotRecover() {
        double gain = 32; // a stretch that makes one source step 32 screen levels tall
        double[] truth = ramp(0.30, 0.30 + 4 / 255., 1);
        double[] ideal = new double[N];
        for (int i = 0; i < N; i++)
            ideal[i] = quantizeScreen(0.30 + (truth[i] - 0.30) * gain);

        double noDither = 0;
        for (double strength : new double[]{0, 0.5, 1, 2}) {
            Random random = new Random(5);
            double[] out = new double[N];
            for (int i = 0; i < N; i++) {
                double source = Math.round(truth[i] * 255) / 255.;
                double jittered = source + strength / 255 * (random.nextDouble() - 0.5);
                out[i] = quantizeScreen(0.30 + (jittered - 0.30) * gain);
            }
            double error = meanLocalError(out, ideal);
            double grain = grain(out);
            System.out.println("  source dither " + strength + " bin: error " + fmt(error)
                    + " levels, grain " + fmt(grain) + " levels");
            if (strength == 0) {
                noDither = error;
                expect(error > 4, "the undithered staircase must be visibly wrong to begin with, or "
                        + "this measurement proves nothing; got " + fmt(error));
            } else {
                // The claim: no strength helps. Anything better than a few percent would mean the
                // reasoning above is wrong and a source dither is worth having after all.
                expect(error > 0.95 * noDither,
                        "a source dither of " + strength + " bins must not recover the lost detail, "
                                + "but the error fell from " + fmt(noDither) + " to " + fmt(error));
                // And it costs grain in proportion, everywhere, not only along the contour.
                expect(grain > strength * gain * 0.2,
                        "a source dither of " + strength + " bins must add grain in proportion; got "
                                + fmt(grain) + " screen levels");
            }
        }
    }

    private static double quantizeScreen(double value) {
        return Math.round(Math.clamp(value, 0, 1) * 255) / 255.;
    }

    private static double[] ramp(double from, double to, int unused) {
        double[] out = new double[N];
        for (int i = 0; i < N; i++)
            out[i] = from + (to - from) * i / (N - 1.);
        return out;
    }

    /** Mean |local average of the render - local average of the target|, in screen levels. */
    private static double meanLocalError(double[] rendered, double[] target) {
        double[] a = boxcar(rendered);
        double[] b = boxcar(target);
        double sum = 0;
        for (int i = 0; i < a.length; i++)
            sum += Math.abs(a[i] - b[i]);
        return 255 * sum / a.length;
    }

    /** Standard deviation of what a small boxcar does not explain, in screen levels. */
    private static double grain(double[] values) {
        double[] smooth = boxcar(values);
        int offset = (values.length - smooth.length) / 2;
        double mean = 0;
        for (int i = 0; i < smooth.length; i++)
            mean += values[i + offset] - smooth[i];
        mean /= smooth.length;
        double var = 0;
        for (int i = 0; i < smooth.length; i++) {
            double d = values[i + offset] - smooth[i] - mean;
            var += d * d;
        }
        return 255 * Math.sqrt(var / smooth.length);
    }

    private static double[] boxcar(double[] values) {
        double[] out = new double[values.length - WINDOW + 1];
        double running = 0;
        for (int i = 0; i < WINDOW; i++)
            running += values[i];
        out[0] = running / WINDOW;
        for (int i = 1; i < out.length; i++) {
            running += values[i + WINDOW - 1] - values[i - 1];
            out[i] = running / WINDOW;
        }
        return out;
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

}
