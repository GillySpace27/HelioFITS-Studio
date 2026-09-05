package org.helioviewer.jhv.display;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.opengl.GL;

/**
 * How a frame's pixels are filtered when the texture is magnified.
 *
 * <p>The default is None, which shows the pixels that are actually in the data. Smoothing is a
 * reasonable default for a picture and a poor one for a measurement: at any zoom past 1:1 a linear
 * filter invents values between samples, and the coronal structure this application exists to look
 * at is exactly the kind of faint, small-scale feature that an invented value can imitate. Choose
 * Linear when the smoothness is wanted and the reading of individual pixels is not.
 *
 * <p>Only these two are offered because they are the two the hardware has. Anything better, cubic
 * or Lanczos, is a shader with its own taps, not a texture parameter, and is not implemented.
 *
 * <p>A categorical LUT overrides this to None regardless: blending between two category indices
 * produces a third category, which is not a smoother picture but a wrong one.
 */
public enum Interpolation {

    None("None (show the data's own pixels)", GL.NEAREST),
    Linear("Linear (smooth between pixels)", GL.LINEAR);

    public final String label;
    public final int glFilter;

    Interpolation(String _label, int _glFilter) {
        label = _label;
        glFilter = _glFilter;
    }

    private static final String KEY = "display.interpolation";

    private static Interpolation current = fromName(String.valueOf(Settings.getProperty(KEY)));

    private static Interpolation fromName(String name) {
        for (Interpolation i : values())
            if (i.name().equals(name))
                return i;
        return None;
    }

    public static Interpolation get() {
        return current;
    }

    public static void set(Interpolation _interpolation) {
        current = _interpolation;
        Settings.setProperty(KEY, _interpolation.name());
    }

    /** The filter to upload with, given whether this layer's LUT is categorical. */
    public static int glFilter(boolean categoricalLUT) {
        return categoricalLUT ? GL.NEAREST : current.glFilter;
    }

}
