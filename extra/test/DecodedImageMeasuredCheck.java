package org.helioviewer.jhv.image;

import org.helioviewer.jhv.metadata.Region;

/** DecodedImage.measured counts a frame's levels on the calling (decode) thread, so the EDT never has to. */
public class DecodedImageMeasuredCheck {

    private static int failures;

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        Region region = new Region(0, 0, 4, 4);
        short[] data = new short[16];
        for (int i = 0; i < data.length; i++)
            data[i] = Float.floatToFloat16(i / 15f);

        ImageBuffer plain = ImageBuffer.fromShorts(4, 4, ImageBuffer.Format.Gray16F, data.clone(), ImageFilter.NONE);
        new DecodedImage(plain, region);
        check("a plain DecodedImage leaves the count to the EDT", plain.levelsMeasured(), false);

        ImageBuffer counted = ImageBuffer.fromShorts(4, 4, ImageBuffer.Format.Gray16F, data.clone(), ImageFilter.NONE);
        DecodedImage image = DecodedImage.measured(counted, region);
        check("measured() counts before returning", counted.levelsMeasured(), true);
        check("measured() keeps the buffer", image.imageBuffer() == counted, true);

        System.out.println(failures == 0 ? "DecodedImageMeasuredCheck: PASS" : "DecodedImageMeasuredCheck: FAIL");
        if (failures != 0)
            System.exit(1);
    }

    private static void check(String what, Object got, Object want) {
        boolean ok = got.equals(want);
        if (!ok)
            failures++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + ": got " + got + ", want " + want);
    }

}
