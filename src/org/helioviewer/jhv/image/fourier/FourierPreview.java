package org.helioviewer.jhv.image.fourier;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.helioviewer.jhv.image.DecodedImage;
import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.view.View;

/**
 * A velocity filter fast enough to drag a band and watch the movie answer.
 *
 * <p>A full run is half a minute: it reads every frame, resamples the movie onto a polar cube of
 * some 512 x 256 x 256, transforms every slice, and then back-projects and packs all 245 frames at
 * full size. Nothing about that can be made interactive. What can is the equaliser's trick: the
 * reading and resampling are done once, and a band change is one mask over that cube rather than
 * a fresh resample. The masked cube then already holds every frame; projecting one back at preview
 * size is milliseconds, so the movie can be played through the band rather than looked at one
 * frame at a time.
 *
 * <p>The polar grid is the SAME one the full run builds (params.nR() x params.nPhi()), on request:
 * the preview answers "what will Apply do with this band", and a coarser grid was a different,
 * softer question. A band change costs more for it (tens of milliseconds rather than a handful),
 * which is the one price of the accurate answer.
 *
 * <p>The time grid is NOT coarsened. It is what sets the rate axis: dt fixes the highest
 * resolvable rate and the number of time samples fixes the resolution in rate, so a preview on a
 * coarser time grid would answer a different question from the one Apply answers, which is the one
 * failure a preview must not have. Only the spatial detail is approximate, and it shows: the
 * preview is smooth where the full run is sharp.
 *
 * <p>Built through FourierJob.build, the same code the full run uses, for the same reason.
 */
public final class FourierPreview {

    // Preview frames are packed at no more than this on the long side. Independent of the polar
    // grid, which now matches the full run's: this bounds the pixel-grid work of one projection,
    // not the detail the cube itself can resolve.
    private static final int MAX_SIDE = 768;

    private final View source;
    private final FourierJob.Prepared prep;
    private final float[][] pristine; // the resampled cube before any mask, restored before each filter
    @Nullable private FourierParams current; // what the cube is filtered with now
    private double amplitude = 1;

    private FourierPreview(View _source, FourierJob.Prepared _prep) {
        source = _source;
        prep = _prep;
        float[][] data = prep.cube().data;
        pristine = new float[data.length][];
        for (int s = 0; s < data.length; s++)
            pristine[s] = data[s].clone();
    }

    /** Read the movie once onto the coarse grid. Seconds, and then every band is cheap. */
    public static FourierPreview prepare(View source, FourierParams params, Consumer<String> status) throws Exception {
        return new FourierPreview(source, FourierJob.build(source, params, params.nR(), params.nPhi(), status, p -> {}, 0));
    }

    /** The polar grid this is previewing on, for the readout that has to admit it is coarse. */
    public String grid() {
        PolarCube cube = prep.cube();
        return cube.nR + " x " + cube.nPhi + " x " + cube.nT;
    }

    /**
     * Filter the cube with these parameters: the one step a band change costs.
     *
     * <p>The amplitude of a PASS output is measured from this cube, exactly as the full run
     * measures it from its own, so the preview is scaled like the thing it is previewing.
     * Synchronised against {@link #frame}, which reads the cube this writes.
     */
    public synchronized void filter(FourierParams params) throws Exception {
        PolarCube cube = prep.cube();
        for (int s = 0; s < pristine.length; s++)
            System.arraycopy(pristine[s], 0, cube.data[s], 0, pristine[s].length);
        FourierFilter.filterCube(cube, params, prep.dInner(), prep.dt());
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException();
        amplitude = FourierJob.amplitude(cube);
        current = params;
    }

    /** What the cube is filtered with, or null before the first filter. */
    @Nullable
    public synchronized FourierParams current() {
        return current;
    }

    /**
     * Frame k from the filtered cube, at preview size, or null when the source cannot produce
     * it or nothing has been filtered yet.
     */
    @Nullable
    public synchronized DecodedImage frame(int k) {
        FourierParams params = current;
        if (params == null)
            return null;
        FrameStack.Frame f = FrameStack.frame(source, k);
        if (f == null)
            return null;
        PolarCube cube = prep.cube();
        boolean notch = params.mode() == FourierParams.Mode.NOTCH;
        int step = Math.max(1, (int) Math.ceil(Math.max(f.width(), f.height()) / (double) MAX_SIDE));
        int w = Math.max(1, f.width() / step), h = Math.max(1, f.height() / step);
        float[] values = new float[w * h];
        double u = (prep.times()[k] - prep.times()[0]) / 1000. / prep.dt();
        // Each polar cell as a block, not blended: the preview should look like the grid it is.
        // The source's own per-pixel mask is NOT applied here, unlike the full run. Sampled at one
        // source pixel per preview pixel it drew every flagged pixel as a speck; regions with
        // nothing in them are already missing from the cube (a cell is valid only when more than
        // half its samples were present).
        cube.toCartesian(values, w, h, f.sunCentred(), u, notch, true);
        ImageBuffer buffer = notch ? FrameStack.packLike(f, w, h, values) : FrameStack.packSigned(f, w, h, values, amplitude);
        return new DecodedImage(buffer, f.decoded().region());
    }

    /** One frame under these parameters: filter if they are new, then project the frame. */
    @Nullable
    public DecodedImage render(FourierParams params, int frame) throws Exception {
        synchronized (this) {
            if (!params.equals(current))
                filter(params);
        }
        return frame(frame);
    }

}
