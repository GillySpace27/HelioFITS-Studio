package org.helioviewer.jhv.layers.filters;

import javax.annotation.Nullable;

import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.image.ImageDisplaySettings;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.view.View;

/**
 * The Levels window in the data's own units: which value is black and which is white.
 *
 * <p>The Levels handles say where the data's ends land in grey, and the 2026-10-02 working
 * meeting asked to see the values instead: "20% to 80%" names no value in the data. The shader shows
 * texture * gain + offset (imageCommon.frag, fetch), gain being brightScale times the response
 * factor, so black is the texture value where that is 0 and white where it is 1, and the frame's
 * PhysicalScale turns a texture value into data, as the colorbar's hover readout does.
 */
public final class AbsoluteLevels {

    /** The texture values shown as black and as white, {black, white}. */
    static double[] window(double offset, double brightScale, double response) {
        double gain = brightScale * response;
        return new double[]{-offset / gain, (1 - offset) / gain};
    }

    /** The {offset, brightScale} that put black and white on these texture values, or null if black is not below white. */
    @Nullable
    static double[] brightness(double texBlack, double texWhite, double response) {
        if (!(texWhite > texBlack))
            return null;
        double gain = 1 / (texWhite - texBlack);
        return new double[]{-texBlack * gain, gain / response};
    }

    /** Two lines, black over white, in the data's units, as the percent label had them. */
    static String label(ImageBuffer.PhysicalScale scale, double offset, double brightScale, double response) {
        double[] w = window(offset, brightScale, response);
        return "<html><p align='right'>" + value(scale, w[0]) + "</p><p align='right'>" + value(scale, w[1]) + "</p>";
    }

    // Below the texture's 0 there is no value to name (the stretch is not defined there), only
    // that black sits under the darkest one the frame has. Above 1 the decoder kept the ratio, and
    // toPhysical carries on past the top of the range.
    static String value(ImageBuffer.PhysicalScale scale, double tex) {
        return tex < 0 ? "< " + format(scale.toPhysical(0)) : format(scale.toPhysical(tex));
    }

    /**
     * A data value to the texture value that shows it. Outside the frame's range it continues in a
     * straight line, as toPhysical does above the top, where toDisplay would pin it to an end.
     */
    static double toTexture(ImageBuffer.PhysicalScale scale, double value) {
        double lo = scale.min(), hi = scale.max();
        return value < lo || value > hi ? (value - lo) / (hi - lo) : scale.toDisplay(value);
    }

    static String format(double v) {
        return String.format("%.3g", v);
    }

    /**
     * The scale that turns this layer's texture into data, or null where no data value exists to
     * show: a server-rendered image never had one, and RHEF's rank and a difference have no inverse.
     */
    @Nullable
    static ImageBuffer.PhysicalScale scaleOf(ImageLayer layer) {
        ImageDisplaySettings s = layer.getDisplaySettings();
        if (layer.getFilter() == ImageFilter.Type.RHEF || s.getDifferenceMode() != ImageDisplaySettings.DifferenceMode.None)
            return null;
        View.ImageData data = layer.getImageData();
        if (data == null || data.metaData().getResponseFactor() == 0 || s.getBrightScale() == 0)
            return null;
        return data.imageBuffer().physicalScale();
    }

    static double response(ImageLayer layer) {
        return layer.getImageData().metaData().getResponseFactor();
    }

    private AbsoluteLevels() {}
}
