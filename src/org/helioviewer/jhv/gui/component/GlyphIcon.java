package org.helioviewer.jhv.gui.component;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.util.Map;

import javax.swing.Icon;
import javax.swing.UIManager;

import org.helioviewer.jhv.gui.UIGlobals;

import com.formdev.flatlaf.FlatLaf;

/**
 * One Material Design codepoint, painted as an icon rather than smuggled in as HTML.
 *
 * <p>These glyphs used to reach the buttons as {@code "<html><font face='Material Design
 * Icons' size=5></font>"}. That works, but everything downstream then treats the icon as
 * text: the look-and-feel cannot recolour it for hover, selection or disabled, the gap between
 * glyph and label is spelled with {@code &nbsp;}, the two-line toolbar button depends on a
 * {@code <br/>}, and the icon-only mode is a different HTML string rather than the same button
 * with its text taken away. As an Icon it is laid out by the button and coloured by the theme.
 *
 * <p>The colour is the component's own foreground, so a selected toolbar toggle and a label in a
 * list row both get the glyph in whatever colour their text is in. Disabled is the one state
 * that does not follow from that, because Swing leaves getForeground() alone on a disabled
 * button; FlatLaf asks a DisabledIconProvider for its own greyed variant, which is what the
 * second constructor is for.
 *
 * <p>The font is derived on first use, not in the constructor: these are static finals in
 * {@link Buttons}, and the icon font is only registered once the look-and-feel is installed.
 */
public final class GlyphIcon implements Icon, FlatLaf.DisabledIconProvider {

    private static final BufferedImage MEASURE = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

    private final String glyph;
    private final float size;
    private final boolean disabled;

    private Font font;
    private int width;
    private int height;
    private int baseline;

    GlyphIcon(MaterialDesign md, float _size) {
        this(md.toString(), _size, false);
    }

    private GlyphIcon(String _glyph, float _size, boolean _disabled) {
        glyph = _glyph;
        size = _size;
        disabled = _disabled;
    }

    /** The same glyph at another size, for the places that ask for a bigger transport button. */
    public GlyphIcon derive(float newSize) {
        return new GlyphIcon(glyph, newSize, disabled);
    }

    @Override
    public Icon getDisabledIcon() {
        return new GlyphIcon(glyph, size, true);
    }

    private void measure() {
        if (font != null)
            return;
        // Falls back to a plain font when the icon font is not registered, which is the case in
        // the headless checks: measuring must not be the thing that throws there.
        Font base = UIGlobals.uiFontMDI;
        font = base == null ? new Font(Font.SANS_SERIF, Font.PLAIN, Math.round(size)) : base.deriveFont(size);
        FontMetrics fm = MEASURE.createGraphics().getFontMetrics(font);
        width = fm.stringWidth(glyph);
        height = fm.getAscent() + fm.getDescent();
        baseline = fm.getAscent();
    }

    @Override
    public int getIconWidth() {
        measure();
        return width;
    }

    @Override
    public int getIconHeight() {
        measure();
        return height;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        measure();
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            if (Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints") instanceof Map<?, ?> hints)
                g2.addRenderingHints((Map<?, ?>) hints);
            g2.setFont(font);
            g2.setColor(disabled ? disabledColor() : c.getForeground());
            g2.drawString(glyph, x, y + baseline);
        } finally {
            g2.dispose();
        }
    }

    private static Color disabledColor() {
        Color color = UIManager.getColor("Button.disabledText");
        return color == null ? Color.GRAY : color;
    }

}
