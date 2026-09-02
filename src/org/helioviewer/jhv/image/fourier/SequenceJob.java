package org.helioviewer.jhv.image.fourier;

import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

import javax.annotation.Nullable;

import org.helioviewer.jhv.image.DecodedImage;
import org.helioviewer.jhv.view.View;

/**
 * One run of a sequence filter, off the EDT on a Task worker: every frame of the source in, one
 * decoded frame per source frame out, index-aligned so the view's times and counts do not change.
 * Implementations poll Thread.interrupted() between frames and slices so Cancel works.
 */
public interface SequenceJob {

    DecodedImage[] run(View source, Consumer<String> status, DoubleConsumer progress) throws Exception;

    /** A diagnostic spectrum gathered during run(), or null when the filter has none. */
    @Nullable
    default FourierFilter.Spectrum spectrum() {
        return null;
    }

}
