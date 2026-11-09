package org.helioviewer.jhv.gui;

import java.awt.Color;
import java.awt.image.BufferedImage;

import org.helioviewer.jhv.app.Theme;

/**
 * The presentation pointer: a bright arrow with a dark outline, visible on black and on white, with
 * its tip at the hotspot, sharp at 2x, and chosen only while presenting.
 *
 * <p>Run: java -cp "bin:extra/test-classes:resources:lib/*" org.helioviewer.jhv.gui.PresentationCursorCheck
 */
public final class PresentationCursorCheck {

    private static int failures;

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok)
            failures++;
    }

    public static void main(String[] args) {
        BufferedImage one = PresentationCursor.image(1), two = PresentationCursor.image(2);
        expect("1x and 2x images are the stated sizes", one.getWidth() == PresentationCursor.SIZE && two.getWidth() == 2 * PresentationCursor.SIZE);

        int white = 0, dark = 0, opaque = 0;
        for (int y = 0; y < one.getHeight(); y++)
            for (int x = 0; x < one.getWidth(); x++) {
                int argb = one.getRGB(x, y);
                if ((argb >>> 24) < 250)
                    continue;
                opaque++;
                Color c = new Color(argb, true);
                if (Theme.contrast(c, Color.BLACK) > 18)
                    white++;
                else if (Theme.contrast(c, Color.WHITE) > 18)
                    dark++;
            }
        expect("the arrow has a bright body (" + white + " px) and a dark outline (" + dark + " px)", white > 150 && dark > 100);
        expect("so on a black sky the body stands out at over 18:1, and on a white frame the outline does too", white > 0 && dark > 0);
        expect("it is bigger than the system arrow (about 12 x 19 px): " + opaque + " opaque px", opaque > 400);

        int tip = one.getRGB(PresentationCursor.HOTSPOT.x + 2, PresentationCursor.HOTSPOT.y + 4);
        expect("the tip is at the hotspot, so a click lands where the arrow points", (tip >>> 24) > 200);
        int outside = one.getRGB(PresentationCursor.SIZE - 1, PresentationCursor.SIZE - 1);
        expect("and the corner away from the arrow is clear", (outside >>> 24) == 0);

        expect("it is the pointer while presenting, and the system's own otherwise",
                PresentationCursor.forMode(java.awt.Cursor.getDefaultCursor()) == java.awt.Cursor.getDefaultCursor() && !PresentationMode.isActive());

        System.out.println(failures == 0 ? "PresentationCursorCheck: PASS" : "PresentationCursorCheck: " + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private PresentationCursorCheck() {}
}
