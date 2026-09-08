package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Rectangle;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

/**
 * The view a sidebar's scroll pane holds: it lays its contents out at the sidebar's width rather
 * than at whatever width they ask for, and never scrolls sideways.
 *
 * <p>Swing widgets report a minimum width they will not go below, and for the ones a settings
 * panel is made of that minimum is simply their full label: a JCheckBox called "Show the residual
 * (what was removed)" will not go under 271 px, a radio called "Instrument distance" under 158,
 * and a row of them adds up. Measured, the camera palette's content claims a minimum of 392 px and
 * the grid's 351, when the controls themselves would sit quite happily in half that. Believing
 * those numbers is what made a sidebar start scrolling sideways long before it had actually run
 * out of room, which is the complaint this answers.
 *
 * <p>So this does not believe them. It reports no minimum width of its own, and tells the viewport
 * it always tracks its width, which lays the contents out at whatever the sidebar currently is.
 * What compresses (sliders, combo boxes, fields, panels) compresses; what genuinely cannot (a long
 * label) is clipped at the edge rather than pushing a scrollbar in front of everything else. That
 * is the trade Gilly asked for, in his words: horizontal scrolling should never be a thing on
 * either bar.
 *
 * <p>Height is not tracked, so the sections keep their heights and the vertical scrollbar still
 * appears when the stack is taller than the sidebar, which is the axis a sidebar is meant to
 * scroll on.
 */
@SuppressWarnings("serial")
public final class SqueezeView extends JPanel implements Scrollable {

    public SqueezeView(JComponent content) {
        super(new BorderLayout());
        setOpaque(false);
        add(content, BorderLayout.PAGE_START);
    }

    /**
     * No minimum width of its own, so nothing upstream inflates the sidebar on behalf of a label
     * inside it. Without this the scroll pane hands the viewport a minimum the sidebar then has to
     * honour, and the squeeze never happens.
     */
    @Override
    public Dimension getMinimumSize() {
        Dimension size = super.getMinimumSize();
        size.width = 0;
        return size;
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
    }

    /** The width a viewport this wide actually gives the contents. Pure, for SidebarSqueezeCheck. */
    public static int contentWidth(int viewportWidth) {
        return Math.max(0, viewportWidth);
    }

}
