package org.helioviewer.jhv.gui.component;

import java.awt.Graphics;

import javax.swing.BorderFactory;
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
