package org.helioviewer.jhv.image.fourier;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.helioviewer.jhv.image.DecodedImage;
import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.view.View;

/**
 * One frame of a velocity filter, fast enough to drag a band and watch the picture answer.
 *
 * <p>A full run is half a minute: it reads every frame, resamples the movie onto a polar cube of
 * some 512 x 256 x 256, transforms every slice, and then back-projects and packs all 245 frames.
 * Nothing about that can be made interactive. What can is the question actually being asked while
 * a band is dragged, which is "what does THIS frame look like": the reading and resampling are
 * done once, the polar grid is dropped to a quarter in each direction, and only the displayed
 * frame is back-projected. The transform is then about a sixteenth of the work and the
 * back-projection one frame instead of 245.
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

    // A quarter of the default grid in each direction: a sixteenth of the transform.
    private static final int NR = 128, NPHI = 64;

    private final View source;
    private final FourierJob.Prepared prep;
    private final float[][] pristine; // the resampled cube before any mask, restored before each render

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
        return new FourierPreview(source, FourierJob.build(source, params, NR, NPHI, status, p -> {}, 0));
    }

    /** The polar grid this is previewing on, for the readout that has to admit it is coarse. */
    public String grid() {
        PolarCube cube = prep.cube();
        return cube.nR + " x " + cube.nPhi + " x " + cube.nT;
    }

    /**
     * One frame under these parameters, or null when the source cannot produce it.
     *
     * <p>Not thread safe: it filters the one cube in place, so the caller runs one at a time. The
     * amplitude of a PASS frame is measured from this cube, exactly as the full run measures it
     * from its own, so the preview is scaled like the thing it is previewing.
     */
    @Nullable
    public DecodedImage render(FourierParams params, int frame) throws Exception {
        PolarCube cube = prep.cube();
        for (int s = 0; s < pristine.length; s++)
            System.arraycopy(pristine[s], 0, cube.data[s], 0, pristine[s].length);
        FourierFilter.filterCube(cube, params, prep.dInner(), prep.dt());
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException();

        FrameStack.Frame f = FrameStack.frame(source, frame);
        if (f == null)
            return null;
        boolean notch = params.mode() == FourierParams.Mode.NOTCH;
        double amplitude = FourierJob.amplitude(cube);
        float[] values = new float[f.width() * f.height()];
        double u = (prep.times()[frame] - prep.times()[0]) / 1000. / prep.dt();
        cube.toCartesian(values, f.width(), f.height(), f.sunCentred(), u, notch);
        float[] original = FrameStack.physical(f);
        for (int i = 0; i < values.length; i++)
            if (Float.isNaN(original[i]))
                values[i] = Float.NaN; // the source's own mask wins, as in the full run
        ImageBuffer buffer = notch ? FrameStack.packLike(f, values) : FrameStack.packSigned(f, values, amplitude);
        return new DecodedImage(buffer, f.decoded().region());
    }

}
