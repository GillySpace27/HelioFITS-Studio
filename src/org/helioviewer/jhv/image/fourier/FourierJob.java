package org.helioviewer.jhv.image.fourier;

import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import javax.annotation.Nullable;

import org.helioviewer.jhv.base.ArrayUtils;
import org.helioviewer.jhv.image.DecodedImage;
import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.metadata.Region;
import org.helioviewer.jhv.view.View;

/**
 * A velocity filter over a whole movie: frames to physical units on a uniform time grid, into a
 * polar cube, one 2D transform per slice, and back to each original frame's pixel grid and time.
 *
 * <p>Time grid: dt is the median cadence, samples are linear interpolations between the two
 * bracketing frames, and the output is sampled back at the original frame times, so the view's
 * frame count and times do not change. A PASS output is the filtered fluctuation on a symmetric
 * scale (mid-grey is zero; the amplitude is the 99.5th percentile of the untapered interior
 * divided by the gain); a NOTCH output is the original minus the rejected band, re-encoded the
 * way each frame was stored.
 */
final class FourierJob implements SequenceJob {

    private static final int MIN_FRAMES = 8;
    private static final int MAX_UNIFORM_PER_FRAME = 4; // a gap cannot inflate the time grid past this
    private static final double AMPLITUDE_PERCENTILE = 0.995;
    private static final int MAX_SAMPLES = 1 << 20;
    private static final int SAMPLE_STEP = 104729; // prime, so the sample never locks onto row structure

    private final FourierParams params;
    @Nullable private volatile FourierFilter.Spectrum spectrum;

    FourierJob(FourierParams _params) {
        params = _params;
    }

    @Nullable
    @Override
    public FourierFilter.Spectrum spectrum() {
        return spectrum;
    }

    @Override
    public DecodedImage[] run(View source, Consumer<String> status, DoubleConsumer progress) throws Exception {
        int n = source.getMaximumFrameNumber() + 1;
        if (n < MIN_FRAMES)
            throw new Exception("needs at least " + MIN_FRAMES + " frames, has " + n);
        long[] times = new long[n];
        for (int k = 0; k < n; k++)
            times[k] = source.getFrameTime(k).milli;

        // 1. Time grid.
        double span = (times[n - 1] - times[0]) / 1000.;
        double dt = Math.max(FrameStack.medianCadence(times), span / (MAX_UNIFORM_PER_FRAME * n));
        int nU = (int) Math.round(span / dt) + 1;

        // 2. Geometry from the first frame.
        FrameStack.Frame first = FrameStack.frame(source, 0);
        if (first == null)
            throw new Exception("the source cannot provide whole frames");
        Region sc = first.sunCentred();
        double pixX = sc.width / first.width();
        double rIn = Math.max(0, first.meta().getInnerRadius());
        double inscribed = Math.min(Math.min(Math.abs(sc.llx), Math.abs(sc.urx)), Math.min(Math.abs(sc.lly), Math.abs(sc.ury)));
        double rOut = first.meta().getOuterRadius() > 0 ? Math.min(first.meta().getOuterRadius(), inscribed) : inscribed;
        if (!(rOut > rIn + 4 * pixX))
            throw new Exception("no annulus to filter between " + rIn + " and " + rOut + " solar radii");
        int nR = Math.min(params.nR(), (int) Math.round((rOut - rIn) / pixX));
        int nPhi = params.nPhi();
        // A cube that would not fit shrinks its polar grid before it shrinks the heap.
        long budget = (Runtime.getRuntime().maxMemory() - Runtime.getRuntime().totalMemory() + Runtime.getRuntime().freeMemory()) / 2;
        while (4L * nR * nPhi * nU > budget && (nR > 256 || nPhi > 128)) {
            if (nR > 256)
                nR /= 2;
            else
                nPhi /= 2;
        }
        double dr = (rOut - rIn) / nR;
        PolarCube cube = new PolarCube(params.kind(), nR, nPhi, nU, rIn, dr);
        status.accept(String.format("Fourier filter: %d frames, cadence %.0f s, grid %d x %d x %d", n, dt, nR, nPhi, nU));

        // 3. Read and resample onto the uniform grid, two frames in hand at a time.
        int kA = -1, kB = -1;
        float[] physA = null, physB = null;
        FrameStack.Frame frameA = null;
        for (int j = 0; j < nU; j++) {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException();
            double t = times[0] / 1000. + j * dt;
            int k = 0;
            while (k < n - 2 && times[k + 1] / 1000. <= t)
                k++;
            double tk = times[k] / 1000., tk1 = times[Math.min(k + 1, n - 1)] / 1000.;
            double w = tk1 > tk ? Math.clamp((t - tk) / (tk1 - tk), 0, 1) : 0;
            if (k != kA) {
                if (k == kB) {
                    physA = physB;
                    frameA = FrameStack.frame(source, k);
                } else {
                    frameA = FrameStack.frame(source, k);
                    if (frameA == null)
                        throw new Exception("frame " + k + " unavailable");
                    physA = FrameStack.physical(frameA);
                }
                kA = k;
                physB = null;
                kB = -1;
            }
            int k1 = Math.min(k + 1, n - 1);
            if (w > 0 && k1 != kB) {
                FrameStack.Frame fb = FrameStack.frame(source, k1);
                if (fb == null)
                    throw new Exception("frame " + k1 + " unavailable");
                physB = FrameStack.physical(fb);
                kB = k1;
            }
            float[] sample;
            if (w <= 0 || physB == null) {
                sample = physA;
            } else {
                sample = new float[physA.length];
                for (int i = 0; i < sample.length; i++)
                    sample[i] = (float) ((1 - w) * physA[i] + w * physB[i]); // NaN propagates
            }
            cube.put(j, sample, frameA.width(), frameA.height(), frameA.sunCentred());
            if (j % 4 == 0) {
                status.accept("Fourier filter: reading " + (j + 1) + "/" + nU);
                progress.accept(0.4 * (j + 1) / nU);
            }
        }
        physA = physB = null;
        cube.finish();

        // 4. Transform, mask, invert.
        status.accept("Fourier filter: transforming " + nR + " x " + nPhi + " x " + nU);
        double dInner = dr * FourierParams.KM_PER_RSUN;
        spectrum = FourierFilter.filterCube(cube, params, dInner, dt);
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException();
        progress.accept(0.8);

        // 5. Amplitude from the untapered interior, for the PASS scale, and what the gain does to it.
        Amplitude scale = amplitude(cube, params.gain());
        double amplitude = scale.value;
        if (params.mode() == FourierParams.Mode.PASS)
            // Said out loud because it is the thing that looks like a bug and is not: the gain is a
            // contrast about zero, and everything past the 99.5th percentile divided by it saturates
            // to white or black. At gain 2.7 that was a third of the field.
            org.helioviewer.jhv.app.Log.info(String.format("Fourier filter: gain %.1f saturates %.0f%% of the valid field (99.5th percentile of |v| = %.3g)",
                    params.gain(), 100 * scale.saturated, amplitude));

        // 6. Back to each original frame.
        DecodedImage[] out = new DecodedImage[n];
        boolean notch = params.mode() == FourierParams.Mode.NOTCH;
        for (int k = 0; k < n; k++) {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException();
            FrameStack.Frame f = FrameStack.frame(source, k);
            if (f == null)
                throw new Exception("frame " + k + " unavailable");
            float[] values = new float[f.width() * f.height()];
            double u = (times[k] - times[0]) / 1000. / dt;
            cube.toCartesian(values, f.width(), f.height(), f.sunCentred(), u, notch);
            float[] original = FrameStack.physical(f);
            for (int i = 0; i < values.length; i++)
                if (Float.isNaN(original[i]))
                    values[i] = Float.NaN; // the source's own mask wins
            ImageBuffer buffer = notch ? FrameStack.packLike(f, values) : FrameStack.packSigned(f, values, amplitude / params.gain());
            out[k] = new DecodedImage(buffer, f.decoded().region());
            if (k % 4 == 0) {
                status.accept("Fourier filter: writing " + (k + 1) + "/" + n);
                progress.accept(0.8 + 0.2 * (k + 1) / n);
            }
        }
        return out;
    }

    /** The PASS scale, and the fraction of the sample that a given gain pushes past it. */
    private record Amplitude(double value, double saturated) {}

    // 99.5th percentile of |value| over a prime-stride sample of the valid, untapered interior.
    private static Amplitude amplitude(PolarCube cube, double gain) {
        int nT = cube.nT, nInner = cube.nInner;
        int t0 = (int) (nT * FourierFilter.TUKEY_ALPHA / 2), t1 = nT - t0;
        float[] sample = new float[MAX_SAMPLES];
        int count = 0;
        long total = (long) cube.nSlices * nT * nInner;
        for (long pos = 0; count < MAX_SAMPLES && pos < total * 4; pos += SAMPLE_STEP) {
            long idx = pos % total;
            int s = (int) (idx / (nT * nInner));
            int rem = (int) (idx % (nT * nInner));
            int t = rem / nInner, j = rem % nInner;
            if (t < t0 || t >= t1 || !cube.valid[s][j])
                continue;
            sample[count++] = Math.abs(cube.data[s][rem]);
        }
        if (count == 0)
            return new Amplitude(1, 0);
        float a = ArrayUtils.selectKth(sample, 0, count - 1, (int) (AMPLITUDE_PERCENTILE * (count - 1)));
        double amplitude = a > 0 ? a : 1;
        double limit = amplitude / gain;
        int over = 0;
        for (int i = 0; i < count; i++)
            if (sample[i] > limit)
                over++;
        return new Amplitude(amplitude, over / (double) count);
    }

}
