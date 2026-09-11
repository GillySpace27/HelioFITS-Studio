package org.helioviewer.jhv.gui.component;

import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Two sidebar sections swapping places, drawn as the swap rather than as a cut.
 *
 * <p>Reordering used to be a blink: one frame the panel is here, the next it is there, and which
 * of the two moved is a thing you work out afterwards. This makes it a do-si-do. The two sections
 * slide past each other over a quarter of a second, the one going up stepping slightly to one side
 * and the one going down to the other, so they visibly pass rather than merely exchange. Then the
 * real layout catches up underneath.
 *
 * <p>The mechanism is the standard one for animating what a layout manager owns: paint each
 * section into an image, put the two images on the window's layered pane over the real sections,
 * move the images, run the real reorder at the end, and take the images away once the layout has
 * put the sections where the images already are. The sections themselves are never moved by hand
 * and the layout manager is never suspended, which is what keeps the scroll pane and everything
 * around it none the wiser.
 *
 * <p>Falls back to the plain reorder when there is nothing to animate with: a section not yet on
 * screen, no window, or a swap already in flight.
 */
final class Dosido {

    private static final int DURATION_MS = 260;
    private static final int TICK_MS = 16;
    private static final int BOW = 14; // how far each steps sideways as they pass

    private static boolean running;

    /**
     * @param a      the section being moved
     * @param b      the section it is changing places with
     * @param commit the real reorder, run when the pictures have arrived
     */
    static void swap(Component a, Component b, Runnable commit) {
        JRootPane root = SwingUtilities.getRootPane(a);
        if (running || root == null || !a.isShowing() || !b.isShowing()
                || a.getWidth() <= 0 || a.getHeight() <= 0 || b.getWidth() <= 0 || b.getHeight() <= 0) {
            commit.run();
            return;
        }
        JLayeredPane layer = root.getLayeredPane();
        Rectangle ra = SwingUtilities.convertRectangle(a.getParent(), a.getBounds(), layer);
        Rectangle rb = SwingUtilities.convertRectangle(b.getParent(), b.getBounds(), layer);

        // Whichever is higher ends up below the other, and the lower one takes its place.
        boolean aUpper = ra.y < rb.y;
        Rectangle upper = aUpper ? ra : rb, lower = aUpper ? rb : ra;
        int upperTo = upper.y + lower.height;
        int lowerTo = upper.y;

        JLabel imgUpper = snapshot(aUpper ? a : b, upper);
        JLabel imgLower = snapshot(aUpper ? b : a, lower);
        layer.add(imgUpper, JLayeredPane.POPUP_LAYER);
        layer.add(imgLower, JLayeredPane.POPUP_LAYER);
        running = true;

        long start = System.currentTimeMillis();
        Timer timer = new Timer(TICK_MS, null);
        timer.addActionListener(e -> {
            double t = Math.min(1.0, (System.currentTimeMillis() - start) / (double) DURATION_MS);
            double s = t * t * (3 - 2 * t);          // ease in and out
            double bow = Math.sin(Math.PI * t) * BOW;   // out and back: zero at both ends
            imgUpper.setLocation(upper.x + (int) Math.round(bow), (int) Math.round(upper.y + (upperTo - upper.y) * s));
            imgLower.setLocation(lower.x - (int) Math.round(bow), (int) Math.round(lower.y + (lowerTo - lower.y) * s));
            if (t >= 1.0) {
                timer.stop();
                commit.run();
                // After the layout the commit scheduled, so the sections are underneath the
                // pictures before the pictures go: no frame with neither.
                SwingUtilities.invokeLater(() -> {
                    layer.remove(imgUpper);
                    layer.remove(imgLower);
                    layer.repaint();
                    running = false;
                });
            }
        });
        timer.setRepeats(true);
        timer.start();
    }

    private static JLabel snapshot(Component c, Rectangle where) {
        BufferedImage img = new BufferedImage(where.width, where.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            c.printAll(g); // the whole section, header and contents, as it is on screen right now
        } finally {
            g.dispose();
        }
        JLabel label = new JLabel(new ImageIcon(img));
        label.setBounds(where);
        return label;
    }

    private Dosido() {}
}
