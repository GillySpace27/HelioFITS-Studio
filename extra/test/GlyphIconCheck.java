package org.helioviewer.jhv.gui.component;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;

import javax.swing.Icon;
import javax.swing.JLabel;

import org.helioviewer.jhv.gui.UIGlobals;
import org.helioviewer.jhv.io.FileUtils;

/**
 * The three things a glyph icon has to do that an HTML font string could not.
 *
 * <p>It has to take the colour of whatever it is painted on, so a selected toolbar toggle or a
 * light theme gets the glyph in the right colour. It has to hand FlatLaf a greyed variant, which
 * only happens while GlyphIcon implements DisabledIconProvider: drop that interface and every
 * disabled button quietly keeps a full-strength icon next to greyed-out text, and nothing else
 * in the app would notice. And it has to measure, because the toolbar's overflow chevron decides
 * what fits from preferred widths, and a zero-width icon would make everything appear to fit.
 *
 * <p>Run: java -cp bin:extra/test-classes org.helioviewer.jhv.gui.component.GlyphIconCheck
 */
public final class GlyphIconCheck {

    private static int failures;

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        // The app registers this at start-up; the icons measure against whatever font is there,
        // so the check has to supply it rather than measure a fallback.
        try (InputStream is = FileUtils.getResource("/fonts/materialdesignicons-webfont.ttf")) {
            UIGlobals.uiFontMDI = Font.createFont(Font.TRUETYPE_FONT, is);
        }

        GlyphIcon icon = Buttons.zoomIn;
        expect(icon.getIconWidth() > 0 && icon.getIconHeight() > 0, "a glyph icon measures to a real size");

        Icon bigger = Buttons.zoomIn.derive(36);
        expect(bigger.getIconWidth() > icon.getIconWidth(), "deriving at a larger size gives a larger icon");

        expect(ink(icon, Color.RED) > 0, "the glyph actually paints");
        expect(ink(icon, Color.RED) != ink(icon, Color.BLUE) || pixel(icon, Color.RED) != pixel(icon, Color.BLUE),
                "the glyph takes the component's foreground");

        Icon disabled = icon.getDisabledIcon();
        expect(disabled instanceof GlyphIcon, "FlatLaf is offered a greyed variant of the same glyph");
        expect(pixel(disabled, Color.RED) != pixel(icon, Color.RED),
                "the greyed variant ignores the foreground it would otherwise take");

        System.out.println(failures == 0 ? "GlyphIconCheck: PASS" : "GlyphIconCheck: " + failures + " FAILURE(S)");
        if (failures != 0)
            System.exit(1);
    }

    /** Paint the icon on a label of the given colour and count the pixels it put down. */
    private static int ink(Icon icon, Color foreground) {
        BufferedImage image = paint(icon, foreground);
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++)
                if ((image.getRGB(x, y) >>> 24) != 0)
                    count++;
        return count;
    }

    /** The strongest pixel the icon put down, as a colour to compare against another run. */
    private static int pixel(Icon icon, Color foreground) {
        BufferedImage image = paint(icon, foreground);
        int best = 0, alpha = -1;
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                if ((rgb >>> 24) > alpha) {
                    alpha = rgb >>> 24;
                    best = rgb & 0xFFFFFF;
                }
            }
        return best;
    }

    private static BufferedImage paint(Icon icon, Color foreground) {
        BufferedImage image = new BufferedImage(icon.getIconWidth() + 2, icon.getIconHeight() + 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        JLabel label = new JLabel();
        label.setForeground(foreground);
        icon.paintIcon(label, g, 0, 0);
        g.dispose();
        return image;
    }

    private static void expect(boolean condition, String what) {
        if (!condition) {
            System.out.println("FAIL: " + what);
            failures++;
        }
    }

    private GlyphIconCheck() {}
}
