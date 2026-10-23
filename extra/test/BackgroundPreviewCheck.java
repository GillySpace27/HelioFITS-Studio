package org.helioviewer.jhv.layers.filters;

import java.awt.image.BufferedImage;

/**
 * The Background section's preview draws the background the way the layer draws the frame.
 *
 * <p>FITS stores the bottom row first and FITSData.decode writes row j to height - 1 - j, so the
 * preview must do the same or it shows the background upside down against the image it was taken
 * off. The grey stretch is a display choice (0.5 to 99.5 percentile), checked here so the caption's
 * numbers are the ones used. Synthetic arrays; no LASCO file and no network.
 *
 * <p>Run: java -Djava.awt.headless=true -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.layers.filters.BackgroundPreviewCheck
 */
public final class BackgroundPreviewCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        int w = 4, h = 3;
        float[] values = new float[w * h];
        for (int i = 0; i < w; i++)
            values[i] = 100; // the file's first row: bright
        BufferedImage img = BackgroundPreview.draw(values, w, h, 0, 100);
        expect("the file's first row is drawn at the bottom, as the layer draws it",
                (img.getRGB(0, h - 1) & 0xff) == 255 && (img.getRGB(0, 0) & 0xff) == 0);

        float[] ramp = new float[1001];
        for (int i = 0; i < ramp.length; i++)
            ramp[i] = i;
        ramp[500] = Float.NaN;
        float[] range = BackgroundPreview.stretch(ramp);
        expect(String.format("a 0 to 1000 ramp stretches over about 5 to 995 (got %.1f to %.1f)", range[0], range[1]),
                Math.abs(range[0] - 5) <= 1 && Math.abs(range[1] - 995) <= 1);
        float[] flat = BackgroundPreview.stretch(new float[]{7, 7, 7});
        expect("a flat image still gets a usable range", flat[1] > flat[0]);

        BufferedImage big = BackgroundPreview.draw(new float[1024 * 1024], 1024, 1024, 0, 1);
        expect("a 1024 square background is drawn at 512", big.getWidth() == 512 && big.getHeight() == 512);

        System.out.println(failures == 0 ? "BackgroundPreviewCheck: all passed" : "BackgroundPreviewCheck: " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

}
