package org.helioviewer.jhv.gui;

import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.geom.Path2D;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;

import javax.annotation.Nullable;

/**
 * The pointer in presentation mode: a white arrow with a heavy black outline, half again the usual
 * size, drawn as a custom cursor so it is bright against the black sky and still shows on a white
 * frame (Gilly, 2026-11-09: "visible and bright against the presentation window"). The system's own
 * arrow is small and thin for a room, and Java on a Mac can hide the pointer in full screen; this one
 * is set explicitly on the canvas and on the audience window, and the full-screen hide is switched off.
 *
 * <p>Built as a two-resolution image (1x and 2x) so it stays sharp on a Retina screen.
 */
public final class PresentationCursor {

    /** Size of the cursor image at 1x, in pixels. */
    static final int SIZE = 44;
    /** Where the tip is, at 1x. */
    static final Point HOTSPOT = new Point(2, 2);

    private static final Cursor[] CACHE = new Cursor[1]; // built on first use; a final holder (guards.py ratchet)
    private static final Cursor[] SAVED = new Cursor[1]; // the canvas's own cursor, put back on leaving

    private PresentationCursor() {}

    /** The bright arrow, or the system crosshair where a custom cursor cannot be made (headless). */
    public static synchronized Cursor get() {
        if (CACHE[0] == null) {
            try {
                CACHE[0] = Toolkit.getDefaultToolkit().createCustomCursor(
                        new BaseMultiResolutionImage(image(1), image(2)), HOTSPOT, "presentation");
            } catch (java.awt.HeadlessException | IndexOutOfBoundsException e) {
                CACHE[0] = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR);
            }
        }
        return CACHE[0];
    }

    /** The cursor to show for {@code system} now: the bright arrow while presenting, else {@code system} itself. */
    public static Cursor forMode(Cursor system) {
        return PresentationMode.isActive() ? get() : system;
    }

    /** The arrow drawn at {@code scale} times 1x. Package-private for the check. */
    static BufferedImage image(int scale) {
        BufferedImage img = new BufferedImage(SIZE * scale, SIZE * scale, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.scale(scale, scale);
            double k = 1.7; // the arrow below is 14 x 22 units: about 24 x 37 px, plus the outline
            Path2D arrow = new Path2D.Double();
            arrow.moveTo(0, 0);
            arrow.lineTo(0, 19);
            arrow.lineTo(4.8, 14.9);
            arrow.lineTo(8.0, 22.0);
            arrow.lineTo(11.0, 20.6);
            arrow.lineTo(7.9, 13.8);
            arrow.lineTo(14.0, 13.8);
            arrow.closePath();
            g.translate(HOTSPOT.x, HOTSPOT.y);
            g.scale(k, k);
            g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(java.awt.Color.BLACK);
            g.draw(arrow);
            g.setColor(java.awt.Color.WHITE);
            g.fill(arrow);
        } finally {
            g.dispose();
        }
        return img;
    }

    /** Presentation mode began: the canvas and anything else asked shows the bright arrow, and Java is told not to hide it. */
    static void enter(@Nullable Component canvas) {
        System.setProperty("apple.awt.fullscreenhidecursor", "false");
        if (canvas != null) {
            SAVED[0] = canvas.getCursor();
            canvas.setCursor(get());
        }
    }

    /** Presentation mode ended: the canvas has the cursor it had. */
    static void exit(@Nullable Component canvas) {
        if (canvas != null && SAVED[0] != null)
            canvas.setCursor(SAVED[0]);
        SAVED[0] = null;
    }
}
