package org.helioviewer.jhv.image.fourier;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;

import javax.annotation.Nullable;

import org.helioviewer.jhv.image.DecodedImage;
import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.metadata.MetaData;
import org.helioviewer.jhv.metadata.Region;
import org.helioviewer.jhv.view.View;

/**
 * The frames of a view as a sequence filter needs them: in physical units, with a Sun-centred
 * region, and the way back into a buffer the layer can display.
 *
 * <p>The stored frame is not the data. It is half float, stretched (gamma, asinh, log), scaled
 * per frame, and a bad pixel is exactly 0. PhysicalScale undoes the stretch and scale; a display
 * fraction of 0 or less becomes NaN, the filters' "missing". Gray8 sources (PNG, JPEG) have no
 * physical scale and no missing convention: value / 255, all valid.
 */
public final class FrameStack {

    public record Frame(DecodedImage decoded, MetaData meta, long milli, int width, int height,
                        @Nullable ImageBuffer.PhysicalScale scale, Region sunCentred) {}

    /** Frame k of the source, or null when the view cannot hand it over (a JPEG 2000 stream). */
    @Nullable
    public static Frame frame(View source, int k) {
        DecodedImage decoded = source.frameImage(k);
        if (decoded == null)
            return null;
        ImageBuffer buffer = decoded.imageBuffer();
        if (buffer.format == ImageBuffer.Format.RGBA32)
            throw new IllegalArgumentException("sequence filters need a greyscale source");
        MetaData meta = source.getMetaData(source.getFrameTime(k));
        Region r = decoded.region();
        Vec2 shift = meta.getSunShift();
        Region sunCentred = new Region(r.llx - shift.x, r.lly - shift.y, r.width, r.height);
        return new Frame(decoded, meta, source.getFrameTime(k).milli, buffer.width, buffer.height, buffer.physicalScale(), sunCentred);
    }

    /** The whole frame in physical units, NaN where the decoder stored nothing. */
    public static float[] physical(Frame f) {
        float[] out = new float[f.width * f.height];
        physical(f, 0, 0, f.width, f.height, out);
        return out;
    }

    /** A tile of the frame in physical units into out (row-major, w * h), NaN outside the frame or where missing. */
    public static void physical(Frame f, int x0, int y0, int w, int h, float[] out) {
        ImageBuffer buffer = f.decoded.imageBuffer();
        int fw = f.width, fh = f.height;
        if (buffer.format == ImageBuffer.Format.Gray16F) {
            float[] lut = halfToPhysical(f.scale);
            ShortBuffer sb = (ShortBuffer) buffer.buffer;
            for (int y = 0; y < h; y++) {
                int sy = y0 + y;
                int row = y * w;
                if (sy < 0 || sy >= fh) {
                    Arrays.fill(out, row, row + w, Float.NaN);
                    continue;
                }
                for (int x = 0; x < w; x++) {
                    int sx = x0 + x;
                    out[row + x] = sx < 0 || sx >= fw ? Float.NaN : lut[sb.get(sy * fw + sx) & 0xFFFF];
                }
            }
        } else {
            ByteBuffer bb = (ByteBuffer) buffer.buffer;
            for (int y = 0; y < h; y++) {
                int sy = y0 + y;
                int row = y * w;
                if (sy < 0 || sy >= fh) {
                    Arrays.fill(out, row, row + w, Float.NaN);
                    continue;
                }
                for (int x = 0; x < w; x++) {
                    int sx = x0 + x;
                    out[row + x] = sx < 0 || sx >= fw ? Float.NaN : (bb.get(sy * fw + sx) & 0xFF) / 255f;
                }
            }
        }
    }

    // One table per scale: 65 536 half patterns to physical values, each a pow or log to build,
    // so it is kept for the scale's lifetime. The noise gate asks for the same frame once per
    // tile, and rebuilding the table there cost more than the transforms.
    private static final java.util.Map<ImageBuffer.PhysicalScale, float[]> tables = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static float[] identityTable;

    private static float[] halfToPhysical(@Nullable ImageBuffer.PhysicalScale scale) {
        if (scale == null) {
            if (identityTable == null)
                identityTable = build(null);
            return identityTable;
        }
        return tables.computeIfAbsent(scale, FrameStack::build);
    }

    private static float[] build(@Nullable ImageBuffer.PhysicalScale scale) {
        float[] lut = new float[1 << 16];
        for (int bits = 0; bits < lut.length; bits++) {
            float d = Float.float16ToFloat((short) bits);
            lut[bits] = !(d > 0) || d > 1 ? Float.NaN : scale == null ? d : (float) scale.toPhysical(d);
        }
        return lut;
    }

    /**
     * Physical values back into a buffer the way frame f's decoder stored them: the frame's own
     * stretch and scale, exactly 0 only where the value is missing (a valid pixel at the floor
     * keeps the smallest positive half so it stays valid downstream).
     */
    public static ImageBuffer packLike(Frame f, float[] physical) {
        short[] half = new short[physical.length];
        ImageBuffer.PhysicalScale scale = f.scale;
        for (int i = 0; i < half.length; i++) {
            float v = physical[i];
            if (Float.isNaN(v)) {
                half[i] = 0;
                continue;
            }
            double d = scale == null ? Math.clamp(v, 0, 1) : scale.toDisplay(v);
            half[i] = Float.floatToFloat16((float) Math.max(1e-6, Math.min(1, d)));
        }
        ImageBuffer buffer = ImageBuffer.fromShorts(f.width, f.height, ImageBuffer.Format.Gray16F, half, ImageFilter.of(ImageFilter.Type.None, f.decoded.region(), f.meta));
        buffer.setPhysicalScale(scale);
        return buffer;
    }

    /** A signed fluctuation on the symmetric scale: mid-grey is zero, plus or minus amplitude is white or black. */
    public static ImageBuffer packSigned(Frame f, float[] values, double amplitude) {
        short[] half = new short[values.length];
        double inv = 0.5 / amplitude;
        for (int i = 0; i < half.length; i++) {
            float v = values[i];
            half[i] = Float.isNaN(v) ? 0 : Float.floatToFloat16((float) Math.clamp(0.5 + v * inv, 1e-6, 1));
        }
        ImageBuffer buffer = ImageBuffer.fromShorts(f.width, f.height, ImageBuffer.Format.Gray16F, half, ImageFilter.of(ImageFilter.Type.None, f.decoded.region(), f.meta));
        buffer.setPhysicalScale(new ImageBuffer.PhysicalScale((float) -amplitude, (float) amplitude, y -> y, "Y = t", y -> y));
        return buffer;
    }

    /** Median of successive gaps, in seconds. */
    public static double medianCadence(long[] millis) {
        if (millis.length < 2)
            return 1;
        double[] gaps = new double[millis.length - 1];
        for (int i = 0; i < gaps.length; i++)
            gaps[i] = (millis[i + 1] - millis[i]) / 1000.;
        Arrays.sort(gaps);
        double m = gaps[gaps.length / 2];
        return m > 0 ? m : 1;
    }

    public static double maxGap(long[] millis) {
        double max = 0;
        for (int i = 1; i < millis.length; i++)
            max = Math.max(max, (millis[i] - millis[i - 1]) / 1000.);
        return max;
    }

    private FrameStack() {}

}
