package org.helioviewer.jhv.gui.component;

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

    CollapsiblePaneButton(boolean _child) {
        child = _child;
        setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
        setContentAreaFilled(false);
        setFocusPainted(false);
        setBorderPainted(false);
        setHorizontalAlignment(SwingConstants.LEFT);
        // The look-and-feel's own label colour is chosen against the panel, not against this band,
        // so the title has to be told which of the two it is sitting on.
        UIGlobals.themed(this, c -> c.setForeground(Theme.current().get(Theme.Token.HeaderText)));
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
        g.setColor(Theme.current().get(child ? Theme.Token.ChildHeaderFill : Theme.Token.HeaderFill));
        g.fillRect(0, 0, getWidth(), h - 1);
        // A hairline of the panel colour along the bottom. Six collapsed sections sit directly on
        // top of one another, and at one fill with no gap they paint as a single slab with titles
        // written on it rather than as six bands. The groove is what makes them read as separate.
        g.setColor(Theme.current().get(Theme.Token.Background));
        g.fillRect(0, h - 1, getWidth(), 1);
        super.paintComponent(g);
    }

}
