package org.helioviewer.jhv.image;

/**
 * The measured-depth statistic has to separate an 8-bit browse product from real 16-bit data
 * after both have landed in the same Gray16F buffer, which is the only reason it exists.
 *
 * <p>The interesting case is the third one. The sampler used to walk the image by a constant
 * stride, and a stride divides into the row width: on a 4096-wide ramp tiled down the rows it
 * visited the same 256 columns of every row and reported 256 levels for data that had thousands,
 * which is exactly the answer that would have made a genuine 16-bit layer look 8-bit. Any future
 * sampler that locks onto row structure fails here.
 *
 * <p>Run: java -cp bin:extra/test-classes:lib/* org.helioviewer.jhv.image.MeasuredDepthCheck
 */
public final class MeasuredDepthCheck {

    private static int failures;

    public static void main(String[] args) {
        // A flat ramp of N distinct values, quantized as an 8-bit source would be.
        check("8-bit ramp, one row", levels(quantized(4096, 1, 256)), 256);
        check("8-bit ramp, tiled rows", levels(quantized(4096, 512, 256)), 256);

        // The aliasing case: many distinct values, arranged so a constant stride would miss them.
        int wide = levels(quantized(4096, 512, 65536));
        check("16-bit ramp beats 8-bit by a lot", wide > 1000, true);

        // A single-valued frame is one level, not zero, and must not divide by anything.
        check("flat frame", levels(quantized(64, 64, 1)), 1);

        System.out.println(failures == 0 ? "MeasuredDepthCheck: PASS" : "MeasuredDepthCheck: FAIL");
        if (failures != 0)
            System.exit(1);
    }

    /** width x height of a horizontal ramp carrying exactly {@code steps} distinct values. */
    private static ImageBuffer quantized(int width, int height, int steps) {
        short[] data = new short[width * height];
        for (int x = 0; x < width; x++) {
            int step = steps == 1 ? 0 : (int) ((long) x * steps / width); // 0 .. steps-1
            float value = steps == 1 ? .5f : step / (float) (steps - 1);
            short half = Float.floatToFloat16(value);
            for (int y = 0; y < height; y++)
                data[y * width + x] = half;
        }
        return ImageBuffer.fromShorts(width, height, ImageBuffer.Format.Gray16F, data, ImageFilter.NONE);
    }

    private static int levels(ImageBuffer buffer) {
        return buffer.measuredLevels();
    }

    private static void check(String what, Object got, Object want) {
        boolean ok = got.equals(want);
        if (!ok)
            failures++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + ": got " + got + ", want " + want);
    }

}
