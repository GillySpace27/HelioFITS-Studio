package org.helioviewer.jhv.movie;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/**
 * A caption in the corner of a panel. The time stamp is drawn by the GL text renderer, which has
 * no AWT counterpart, so this uses the sans-serif bold face at a size relative to the panel.
 * The label is taken as given; once NamePattern exists the caller expands its fields first.
 */
public final class PanelLabel {

    public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private PanelLabel() {
    }

    /** Text height as a fraction of the panel's short side. */
    static final double SIZE_FRACTION = 0.045;

    /** Where draw() puts the caption's box (text plus padding) in a panel, so a check can look there. */
    public static Rectangle box(Graphics2D g, String text, Rectangle panel, Corner corner) {
        Font font = font(panel);
        FontMetrics fm = g.getFontMetrics(font);
        int pad = Math.max(2, fm.getHeight() / 4);
        int w = fm.stringWidth(text) + 2 * pad, h = fm.getHeight() + pad;
        int x = corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT ? panel.x + pad : panel.x + panel.width - w - pad;
        int y = corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT ? panel.y + pad : panel.y + panel.height - h - pad;
        return new Rectangle(x, y, w, h);
    }

    public static void draw(Graphics2D g, String text, Rectangle panel) {
        draw(g, text, panel, Corner.TOP_LEFT);
    }

    public static void draw(Graphics2D g, String text, Rectangle panel, Corner corner) {
        if (text == null || text.isEmpty())
            return;
        Font font = font(panel);
        g.setFont(font);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Rectangle b = box(g, text, panel, corner);
        FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(0, 0, 0, 140)); // keeps white text legible on a bright disk
        g.fillRect(b.x, b.y, b.width, b.height);
        g.setColor(Color.WHITE);
        g.drawString(text, b.x + (b.width - fm.stringWidth(text)) / 2, b.y + fm.getAscent() + (b.height - fm.getHeight()) / 2);
    }

    private static Font font(Rectangle panel) {
        int px = Math.max(10, (int) Math.round(Math.min(panel.width, panel.height) * SIZE_FRACTION));
        return new Font(Font.SANS_SERIF, Font.BOLD, px);
    }
}
