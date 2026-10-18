package org.helioviewer.jhv.gui;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;

import javax.imageio.ImageIO;

/** Copy Frame's pure part: a 16-bit PNG screenshot becomes plain 8-bit RGB for the clipboard. */
public final class FrameCopyCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) throws Exception {
        BufferedImage gray16 = new BufferedImage(3, 2, BufferedImage.TYPE_USHORT_GRAY);
        gray16.getRaster().setSample(0, 0, 0, 65535);
        gray16.getRaster().setSample(1, 0, 0, 0);
        File dir = Files.createTempDirectory("framecopy").toFile();
        File png = new File(dir, "shot.png");
        ImageIO.write(gray16, "png", png);
        BufferedImage out = FrameCopy.readRgb8(png);
        expect("size is kept", out.getWidth() == 3 && out.getHeight() == 2);
        expect("the result is plain RGB", out.getType() == BufferedImage.TYPE_INT_RGB);
        expect("16-bit white is 8-bit white", (out.getRGB(0, 0) & 0xFFFFFF) == 0xFFFFFF);
        expect("16-bit black is 8-bit black", (out.getRGB(1, 0) & 0xFFFFFF) == 0);
        boolean refused = false;
        try {
            File junk = new File(dir, "junk.png");
            Files.writeString(junk.toPath(), "not an image");
            FrameCopy.readRgb8(junk);
        } catch (java.io.IOException e) {
            refused = true;
        }
        expect("a file that is not an image is refused, not pasted", refused);
        if (failures > 0) {
            System.out.println("FrameCopyCheck: " + failures + " failed");
            System.exit(1);
        }
        System.out.println("FrameCopyCheck: all passed");
    }
}
