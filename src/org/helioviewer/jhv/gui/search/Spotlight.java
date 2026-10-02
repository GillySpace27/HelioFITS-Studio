package org.helioviewer.jhv.gui.search;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;
import java.util.Objects;

import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import org.helioviewer.jhv.app.Theme;
import org.helioviewer.jhv.gui.MainFrame;

/**
 * The main window dimmed except for one control, with a card beside it.
 *
 * <p>A window of its own over the main one rather than its glass pane: the image canvas is a
 * heavyweight AWT canvas drawn by a native layer, and nothing painted in the glass pane shows over
 * it. Where per-pixel translucency is missing (some Linux desktops) only the card is shown, still
 * beside its control. A prompt ({@code dim} false) is the card alone, in the target's lower right
 * corner.
 *
 * <p>The target is re-measured every {@link #FOLLOW_MS}, so the cut-out follows a sidebar that is
 * still opening, a window being moved or resized, and a theme switch.
 */
final class Spotlight {

    private static final Color DIM = new Color(0, 0, 0, 150);
    private static final int PAD = 6;     // cut-out margin around the target
    private static final int GAP = 12;    // between the cut-out and the card
    private static final int MARGIN = 16; // a prompt's distance from its corner
    private static final int ARC = 12;
    private static final int FOLLOW_MS = 200;

    private final boolean dim;
    private final JDialog window;
    @Nullable
    private final Overlay overlay; // null when only the card is shown
    private final Timer follow = new Timer(FOLLOW_MS, e -> place());

    @Nullable
    private Component target;
    @Nullable
    private JComponent card;
    @Nullable
    private Rectangle placed; // the window bounds last applied, so an unchanged tick repaints nothing
    @Nullable
    private Rectangle placedHole;

    Spotlight(boolean _dim) {
        dim = _dim;
        JFrame frame = MainFrame.get();
        window = new JDialog(frame, Dialog.ModalityType.MODELESS);
        window.setUndecorated(true);
        window.setName("tour");
        if (dim && translucent(frame)) {
            overlay = new Overlay();
            window.getRootPane().putClientProperty("Window.shadow", Boolean.FALSE); // macOS: no shadow round the whole window
            window.getRootPane().setOpaque(false);
            window.setBackground(new Color(0, 0, 0, 0));
            window.setContentPane(overlay);
        } else
            overlay = null;
    }

    /** Escape, back (null for none) and next: the arrow keys and Enter step, Escape leaves. */
    void keys(Runnable escape, @Nullable Runnable back, Runnable next) {
        JRootPane root = window.getRootPane();
        bind(root, "ESCAPE", escape);
        bind(root, "ENTER", next);
        if (back != null) {
            bind(root, "LEFT", back);
            bind(root, "RIGHT", next);
        }
    }

    private static void bind(JRootPane root, String key, Runnable run) {
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), key);
        root.getActionMap().put(key, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                run.run();
            }
        });
    }

    /** Spotlight {@code _target} (null: no cut-out, card centred) and show {@code _card}, focusing {@code focus}. */
    void show(@Nullable Component _target, JComponent _card, JButton focus) {
        target = _target;
        card = _card;
        placed = null;
        placedHole = null;
        if (overlay != null) {
            overlay.removeAll();
            overlay.add(card);
        } else
            window.setContentPane(card);
        place();
        if (!window.isVisible()) {
            window.setVisible(true);
            follow.start();
        }
        window.validate();
        window.repaint();
        focus.requestFocusInWindow();
    }

    void dispose() {
        follow.stop();
        window.dispose();
    }

    private void place() {
        JFrame frame = MainFrame.get();
        if (card == null || frame == null || !frame.isShowing())
            return;
        JRootPane root = frame.getRootPane();
        Rectangle area = onScreen(root, new Rectangle(root.getSize()));
        Rectangle hole = null;
        if (target != null && target.isShowing()) {
            hole = onScreen(target, target instanceof JComponent c ? c.getVisibleRect() : new Rectangle(target.getSize()));
            hole.grow(PAD, PAD);
            hole = hole.intersection(area);
            if (hole.isEmpty())
                hole = null;
        }
        Dimension size = card.getPreferredSize();
        Rectangle at = dim ? beside(size, hole, area) : corner(size, hole == null ? area : hole);

        if (overlay == null) {
            if (!at.equals(placed))
                window.setBounds(at);
            placed = at;
            return;
        }
        if (area.equals(placed) && Objects.equals(hole, placedHole))
            return;
        window.setBounds(area);
        placed = area;
        placedHole = hole;
        overlay.hole = hole == null ? null : new Rectangle(hole.x - area.x, hole.y - area.y, hole.width, hole.height);
        card.setBounds(at.x - area.x, at.y - area.y, at.width, at.height);
        card.validate();
        overlay.repaint();
    }

    private static Rectangle onScreen(Component c, Rectangle r) {
        Point p = r.getLocation();
        SwingUtilities.convertPointToScreen(p, c);
        return new Rectangle(p.x, p.y, r.width, r.height);
    }

    /**
     * Where the card goes: right of the cut-out, else left, below, above, all inside {@code area}
     * and clear of the cut-out; failing those, inside the cut-out's lower right corner (the canvas
     * fills most of the window). Centred when there is no cut-out.
     */
    static Rectangle beside(Dimension size, @Nullable Rectangle hole, Rectangle area) {
        if (hole == null)
            return clamp(new Rectangle(area.x + (area.width - size.width) / 2, area.y + (area.height - size.height) / 2,
                    size.width, size.height), area);
        Rectangle[] tries = {
                new Rectangle(hole.x + hole.width + GAP, hole.y, size.width, size.height),
                new Rectangle(hole.x - GAP - size.width, hole.y, size.width, size.height),
                new Rectangle(hole.x, hole.y + hole.height + GAP, size.width, size.height),
                new Rectangle(hole.x, hole.y - GAP - size.height, size.width, size.height),
        };
        for (Rectangle t : tries) {
            Rectangle r = clamp(t, area);
            if (area.contains(r) && !r.intersects(hole))
                return r;
        }
        return clamp(corner(size, hole), area);
    }

    /** Inside {@code r}, in its lower right corner. */
    static Rectangle corner(Dimension size, Rectangle r) {
        return new Rectangle(r.x + r.width - size.width - MARGIN, r.y + r.height - size.height - MARGIN, size.width, size.height);
    }

    private static Rectangle clamp(Rectangle r, Rectangle area) {
        int x = Math.max(area.x, Math.min(r.x, area.x + area.width - r.width));
        int y = Math.max(area.y, Math.min(r.y, area.y + area.height - r.height));
        return new Rectangle(x, y, r.width, r.height);
    }

    private static boolean translucent(@Nullable JFrame frame) {
        GraphicsConfiguration gc = frame == null ? null : frame.getGraphicsConfiguration();
        return gc != null && gc.getDevice().isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
    }

    /** The dimming, with the cut-out left clear and ringed in the theme's accent. */
    @SuppressWarnings("serial")
    private static final class Overlay extends JComponent {

        @Nullable
        private Rectangle hole;

        Overlay() {
            setLayout(null);
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Area shade = new Area(new Rectangle(getSize()));
            RoundRectangle2D cut = hole == null ? null : new RoundRectangle2D.Double(hole.x, hole.y, hole.width, hole.height, ARC, ARC);
            if (cut != null)
                shade.subtract(new Area(cut));
            g2.setColor(DIM);
            g2.fill(shade);
            if (cut != null) {
                g2.setColor(Theme.current().get(Theme.Token.Accent));
                g2.setStroke(new BasicStroke(2));
                g2.draw(cut);
            }
            g2.dispose();
        }
    }

}
