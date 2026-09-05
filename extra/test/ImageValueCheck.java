package org.helioviewer.jhv.layers;

import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.image.ImageFilter;
import org.helioviewer.jhv.math.Vec2;
import org.helioviewer.jhv.metadata.Region;

/**
 * The footer's value readout has to name the pixel the pointer is actually over.
 *
 * <p>The failure this catches is a vertical flip. Image rows run top-down while a region's origin
 * is its lower-left corner, so getting that wrong still produces a plausible number everywhere and
 * mislabels the whole picture: the reader would be given the value from the mirror image of what
 * they are looking at, with nothing to notice. Every pixel here is distinct and the corners are
 * asymmetric, so a dropped flip fails rather than passes by symmetry.
 *
 * <p>Also pins the two conventions the readout depends on: the sun shift moves the image's frame
 * rather than the pointer, and a pixel stored as exactly zero is a bad or missing FITS sample, so
 * it reads as "--" and not as a measurement of zero.
 *
 * <p>Values are compared as numbers, not strings: the buffer is half float, so 0.33 comes back as
 * 0.330078 and a string comparison would be testing the rounding rather than the mapping.
 *
 * <p>Run: java -cp bin:extra/test-classes:lib/* org.helioviewer.jhv.layers.ImageValueCheck
 */
public final class ImageValueCheck {

    private static int failures;

    public static void main(String[] args) {
        // `sample` below is 4x4 with every pixel distinct, row 0 the TOP row of the picture.
        // The image covers [-2, 2] in both axes, so a pixel is one solar radius across, and the
        // centre of the top-left pixel sits at (-1.5, +1.5).
        Region region = new Region(-2, -2, 4, 4);
        Vec2 origin = new Vec2(0, 0);

        expectValue(1, region, origin, -1.5, 1.5, "top-left of the picture is the FIRST row");
        expectValue(4, region, origin, 1.5, 1.5, "top-right is the first row, last column");
        expectValue(31, region, origin, -1.5, -1.5, "bottom-left is the LAST row, not the first");
        expectValue(34, region, origin, 1.5, -1.5, "bottom-right is the last row, last column");
        expectValue(12, region, origin, -0.5, 0.5, "an interior pixel, row 1 column 1");

        expectNull(region, origin, -2.5, 0, "left of the image reports nothing");
        expectNull(region, origin, 2.5, 0, "right of the image reports nothing");
        expectNull(region, origin, 0, 2.5, "above the image reports nothing");
        expectNull(region, origin, 0, -2.5, "below the image reports nothing");

        // A pixel stored as exactly zero is a bad or missing sample, not a measurement.
        short[] blank = new short[16];
        for (int row = 0; row < 4; row++)
            for (int col = 0; col < 4; col++)
                blank[row * 4 + col] = Float.floatToFloat16((10 * row + col + 1) / 100f);
        blank[0] = Float.floatToFloat16(0);
        ImageBuffer withBlank = ImageBuffer.fromShorts(4, 4, ImageBuffer.Format.Gray16F, blank, ImageFilter.NONE);
        withBlank.setPhysicalScale(new ImageBuffer.PhysicalScale(0, 100, x -> x, "linear"));
        expect("--".equals(ImageLayer.sampleText(withBlank, region, origin, -1.5, 1.5)),
                "a zero pixel reads as missing, not as zero");

        // The sun shift moves the image's frame: with the Sun one radius to the right of where the
        // frame says, the whole picture is read one radius further left.
        Vec2 shift = new Vec2(1, 0);
        expectValue(1, region, shift, -2.5, 1.5, "the shifted frame puts the first column one radius left");
        expectNull(region, shift, 2.5, 1.5, "and takes the far right edge off the data");

        if (failures != 0)
            throw new AssertionError(failures + " value-readout failure(s)");
        System.out.println("ImageValueCheck: PASS");
    }

    private static ImageBuffer sample;

    private static void expectValue(double expected, Region region, Vec2 shift, double x, double y, String what) {
        String text = ImageLayer.sampleText(sample, region, shift, x, y);
        if (text == null || "--".equals(text)) {
            failures++;
            System.out.println("FAIL: " + what + " (got " + text + ", wanted about " + expected + ')');
            return;
        }
        double got = Double.parseDouble(text);
        // Half float carries about three significant digits, so 1 % is generous for the rounding
        // and nowhere near the gap between neighbouring pixels, which differ by whole units.
        expect(Math.abs(got - expected) < 0.01 * expected + 1e-6,
                what + " (got " + got + ", wanted " + expected + ')');
    }

    private static void expectNull(Region region, Vec2 shift, double x, double y, String what) {
        String text = ImageLayer.sampleText(sample, region, shift, x, y);
        expect(text == null, what + " (got " + text + ')');
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    static {
        short[] data = new short[16];
        for (int row = 0; row < 4; row++)
            for (int col = 0; col < 4; col++)
                data[row * 4 + col] = Float.floatToFloat16((10 * row + col + 1) / 100f);
        sample = ImageBuffer.fromShorts(4, 4, ImageBuffer.Format.Gray16F, data, ImageFilter.NONE);
        sample.setPhysicalScale(new ImageBuffer.PhysicalScale(0, 100, x -> x, "linear"));
    }

}
