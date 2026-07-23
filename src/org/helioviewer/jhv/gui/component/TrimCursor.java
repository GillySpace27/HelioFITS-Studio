package org.helioviewer.jhv.gui.component;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;

// A crop-marks glyph shared by every timeline that supports Option-drag trimming (the top
// scrubber and the bottom timeline chart), so the same gesture reads with the same cursor
// everywhere. Drawn to a cursor image (not a font glyph, so it can never fall back to a blank
// box); white halo under black strokes keeps it visible over any track colour.
public final class TrimCursor {

    private static Cursor cursor;

    public static Cursor get() {
        if (cursor != null)
            return cursor;
        try {
            Toolkit tk = Toolkit.getDefaultToolkit();
            Dimension best = tk.getBestCursorSize(28, 28);
            int s = best.width > 0 ? best.width : 28;
            BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float a = s * 0.30f, b = s * 0.70f, o = s * 0.16f; // inner square corners + overhang
            Line2D[] marks = {
                    new Line2D.Float(a - o, a, b, a), // top edge, over-hanging left
                    new Line2D.Float(a, a - o, a, b), // left edge, over-hanging up
                    new Line2D.Float(a, b, b + o, b), // bottom edge, over-hanging right
                    new Line2D.Float(b, a, b, b + o), // right edge, over-hanging down
            };
            for (int pass = 0; pass < 2; pass++) {
                g.setColor(pass == 0 ? Color.WHITE : Color.BLACK);
                g.setStroke(new BasicStroke(pass == 0 ? 4f : 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (Line2D m : marks)
                    g.draw(m);
            }
            g.dispose();
            cursor = tk.createCustomCursor(img, new Point(s / 2, s / 2), "trim");
        } catch (Exception e) {
            cursor = Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR);
        }
        return cursor;
    }

    private TrimCursor() {}
}
