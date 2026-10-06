package org.helioviewer.jhv.gui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

import javax.annotation.Nullable;
import javax.swing.JComponent;
import javax.swing.JFrame;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.opengl.FrameHandoff;
import org.helioviewer.jhv.opengl.PresentationOutput;

/**
 * The projector's picture in presentation mode with two screens: a mirror of the main canvas.
 *
 * <p>Gilly, 2026-10-06: the presenter view should be the main canvas with every control working
 * as usual, not a strip of controls with a small preview. So the main window stays on the
 * presenter's screen untouched, and this full-screen window on the projector shows each frame
 * that {@link PresentationOutput} reads back right after drawing it. The canvas is never moved
 * (moving it would destroy the native Metal host and every GL object; see
 * {@link PresentationMode}), and this window holds no GL at all, so it can be created, made
 * undecorated and disposed freely.
 *
 * <p>It never takes focus and hides the pointer, so the keyboard stays with the main window and
 * nothing but the picture reaches the room.
 */
final class AudienceWindow {

    private final JFrame window;
    private final Mirror mirror = new Mirror();
    @Nullable private GraphicsDevice fullScreenOn;

    AudienceWindow(GraphicsDevice on) {
        window = new JFrame("HelioFITS Studio: Audience", on.getDefaultConfiguration());
        window.setUndecorated(true); // allowed: not displayable yet, and no GL in it
        window.setFocusableWindowState(false);
        window.setAutoRequestFocus(false);
        window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        window.setContentPane(mirror);
        window.setCursor(Toolkit.getDefaultToolkit().createCustomCursor(
                new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), new Point(), "none"));

        Rectangle bounds = on.getDefaultConfiguration().getBounds();
        window.setBounds(bounds);
        window.setVisible(true);
        // Only real full-screen mode takes the menu bar off the projector.
        if (on.isFullScreenSupported()) {
            try {
                on.setFullScreenWindow(window);
                fullScreenOn = on;
            } catch (RuntimeException e) {
                Log.warn("Full screen refused on the projector, showing at screen size instead", e);
            }
        }
        PresentationOutput.OUTPUT.setSink(mirror);
    }

    void close() {
        PresentationOutput.OUTPUT.setSink(null);
        if (fullScreenOn != null) {
            try {
                fullScreenOn.setFullScreenWindow(null);
            } catch (RuntimeException e) {
                Log.warn("Could not leave full screen on the projector cleanly", e);
            }
            fullScreenOn = null;
        }
        window.dispose(); // no GL here: nothing to lose
        mirror.handoff.clear();
    }

    @SuppressWarnings("serial")
    private static final class Mirror extends JComponent implements PresentationOutput.Sink {

        final FrameHandoff handoff = new FrameHandoff();

        Mirror() {
            setOpaque(true);
        }

        @Override
        public boolean wants() {
            return isShowing();
        }

        @Override
        public int[] pixels() {
            AffineTransform t = getGraphicsConfiguration() == null
                    ? new AffineTransform() : getGraphicsConfiguration().getDefaultTransform();
            return new int[]{(int) Math.ceil(getWidth() * t.getScaleX()), (int) Math.ceil(getHeight() * t.getScaleY())};
        }

        @Override
        public FrameHandoff frames() {
            return handoff;
        }

        @Override
        public void framePublished() {
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, getWidth(), getHeight());
            BufferedImage f = handoff.shown();
            if (f != null) {
                int[] box = fitInto(f.getWidth(), f.getHeight(), getWidth(), getHeight());
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.drawImage(f, box[0], box[1], box[2], box[3], null);
                } finally {
                    g2.dispose();
                }
            }
            if (handoff.painted())
                DisplayController.display(); // a frame was skipped while this one waited: draw it now
        }
    }

    /** x, y, width, height of an image of the given size fitted and centred in the box. */
    static int[] fitInto(int imageWidth, int imageHeight, int boxWidth, int boxHeight) {
        double scale = Math.min(boxWidth / (double) Math.max(1, imageWidth), boxHeight / (double) Math.max(1, imageHeight));
        int w = (int) Math.round(imageWidth * scale), h = (int) Math.round(imageHeight * scale);
        return new int[]{(boxWidth - w) / 2, (boxHeight - h) / 2, w, h};
    }

}
