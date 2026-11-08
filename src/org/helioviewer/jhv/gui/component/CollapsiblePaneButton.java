package org.helioviewer.jhv.gui.component;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;

import org.helioviewer.jhv.app.Theme;
import org.helioviewer.jhv.gui.UIGlobals;

/**
 * The band behind a section title.
 *
 * <p>It used to be a two-half gradient built from two static finals computed at class load. That
 * cost twice: the painted surface came out 1.02 to 1.16 against the panel it sat on, which is no
 * band at all, and being static finals the colours could not follow a theme switch. It is a flat
 * fill read from the theme while painting, so the section header is a header and a switch reaches
 * it with no further help.
 */
@SuppressWarnings("serial")
class CollapsiblePaneButton extends JToggleButton {

    private final boolean child;
    /** "L2" or "R2" while that side's modifier is held, else null; drawn in a box, never by changing the text, so nothing moves. */
    @Nullable
    private String badge;

    CollapsiblePaneButton(boolean _child) {
        child = _child;
        setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
        setRolloverEnabled(true);
        setContentAreaFilled(false);
        setFocusPainted(false);
        setBorderPainted(false);
        setHorizontalAlignment(SwingConstants.LEFT);
        // The look-and-feel's own label colour is chosen against the panel, not against this band,
        // so the title has to be told which of the two it is sitting on.
        UIGlobals.themed(this, c -> restyle());
    }

    /**
     * The tooltip with the section's key after it, worked out when it is shown: the key follows the
     * section's place in its sidebar, which changes whenever a section is moved, added or popped out.
     */
    @Override
    public String getToolTipText() {
        String tip = super.getToolTipText();
        if (tip == null || !(javax.swing.SwingUtilities.getAncestorOfClass(CollapsiblePane.class, this) instanceof CollapsiblePane pane))
            return tip;
        String key = PanelKeys.keyText(pane);
        return key == null ? tip : tip + " (" + key + ")";
    }

    /** Show or clear the key number in its box. Repaints only: the text and the layout stay as they were. */
    void setBadge(@Nullable String text) {
        if (!java.util.Objects.equals(badge, text)) {
            badge = text;
            repaint();
        }
    }

    /**
     * Where the box goes: over the number in front of the title when the title carries one, else at the
     * right end of the band (numbers are switched off, but the hint is shown anyway). Null for no badge.
     */
    @Nullable
    java.awt.Rectangle badgeBox(java.awt.FontMetrics fm, boolean overNumber) {
        if (badge == null)
            return null;
        int pad = Math.max(2, fm.getHeight() / 5);
        int textW = fm.stringWidth(badge);
        int h = fm.getAscent() + fm.getDescent();
        java.awt.Insets in = getInsets();
        java.awt.Rectangle viewR = new java.awt.Rectangle(in.left, in.top, getWidth() - in.left - in.right, getHeight() - in.top - in.bottom);
        java.awt.Rectangle iconR = new java.awt.Rectangle(), textR = new java.awt.Rectangle();
        javax.swing.SwingUtilities.layoutCompoundLabel(this, fm, getText(), getIcon(), getVerticalAlignment(), getHorizontalAlignment(),
                getVerticalTextPosition(), getHorizontalTextPosition(), viewR, iconR, textR, getText() == null ? 0 : getIconTextGap());
        int x = overNumber ? textR.x : Math.max(in.left, getWidth() - in.right - textW - 2 * pad - 4);
        int y = textR.y + (textR.height - h) / 2;
        return new java.awt.Rectangle(x - pad, y - 1, textW + 2 * pad, h + 2);
    }

    /** Title colour and inset for the current theme and panel-visibility options. */
    void restyle() {
        Theme theme = Theme.current();
        setForeground(PanelVisibility.headerText(theme, PanelVisibility.headerFill(theme, child)));
        setBorder(BorderFactory.createEmptyBorder(0, child ? 2 : 2 + PanelVisibility.stripeWidth() + (PanelVisibility.stripe() ? 5 : 0), 0, 0));
        revalidate();
        repaint();
    }

    /**
     * The chevron, then the section's own glyph, both in front of the title.
     *
     * <p>A button has one icon slot and one text position, so the two glyphs have to arrive as a
     * single Icon: the alternative is a nested panel, which would cost the button its own layout
     * and its hover and selection painting. The chevron stays first because it is the control and
     * the section glyph is a label.
     */
    void setIcons(Icon chevron, @Nullable Icon section) {
        setIcon(section == null ? chevron : new Pair(chevron, section, getIconTextGap()));
    }

    /** Two icons side by side, each centred on the taller of the pair. */
    private record Pair(Icon first, Icon second, int gap) implements Icon {

        @Override
        public int getIconWidth() {
            return first.getIconWidth() + gap + second.getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return Math.max(first.getIconHeight(), second.getIconHeight());
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            int h = getIconHeight();
            first.paintIcon(c, g, x, y + (h - first.getIconHeight()) / 2);
            second.paintIcon(c, g, x + first.getIconWidth() + gap, y + (h - second.getIconHeight()) / 2);
        }

    }

    @Override
    protected void paintComponent(Graphics g) {
        int h = getHeight();
        Theme theme = Theme.current();
        Color fill = PanelVisibility.headerFill(theme, child);
        if (PanelVisibility.hover() && getModel().isRollover())
            fill = PanelVisibility.hoverFill(theme, fill);
        g.setColor(fill);
        g.fillRect(0, 0, getWidth(), h - 1);
        if (!child && PanelVisibility.stripe()) {
            g.setColor(PanelVisibility.stripeColor(theme, fill));
            g.fillRect(2, 0, PanelVisibility.stripeWidth(), h - 1); // inset: band colour shows to its left, so it reads as a stripe in the band, not a gap in it
        }
        // A hairline of the panel colour along the bottom. Six collapsed sections sit directly on
        // top of one another, and at one fill with no gap they paint as a single slab with titles
        // written on it rather than as six bands. The groove is what makes them read as separate.
        g.setColor(Theme.current().get(Theme.Token.Background));
        g.fillRect(0, h - 1, getWidth(), 1);
        java.awt.FontMetrics fm = getFontMetrics(getFont());
        boolean over = badge != null && getText() != null && getText().startsWith(badge);
        java.awt.Rectangle box = badgeBox(fm, over);
        if (box == null) {
            super.paintComponent(g);
            return;
        }
        // The title is painted everywhere but the box, so the number is drawn once, in the box's colour
        java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
        try {
            if (over) {
                java.awt.geom.Area rest = new java.awt.geom.Area(new java.awt.Rectangle(0, 0, getWidth(), h));
                rest.subtract(new java.awt.geom.Area(box));
                g2.clip(rest);
            }
            super.paintComponent(g2);
        } finally {
            g2.dispose();
        }
        Color[] colors = PanelVisibility.badgeColors(theme, fill);
        java.awt.Graphics2D b = (java.awt.Graphics2D) g.create();
        try {
            b.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            b.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            b.setColor(colors[0]);
            int arc = Math.max(6, fm.getHeight() / 2);
            b.fillRoundRect(box.x, box.y, box.width, box.height, arc, arc);
            b.setFont(getFont());
            b.setColor(colors[1]);
            b.drawString(badge, box.x + (box.width - fm.stringWidth(badge)) / 2, box.y + 1 + fm.getAscent());
        } finally {
            b.dispose();
        }
    }

}
