package org.helioviewer.jhv.gui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import javax.annotation.Nullable;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import org.helioviewer.jhv.opengl.PresentationOutput;

/**
 * A small live copy of the projector's picture, at the top of the presenter window.
 *
 * <p>With two screens the canvas is on the projector and the presenter, facing the laptop, could
 * not see what the room sees (Gilly at Fiske, 2026-09-30). The canvas cannot come here: moving it
 * would run removeNotify() and destroy the native Metal host and every GL object (see
 * {@link PresentationMode}). So this is a lightweight component showing a downscaled readback
 * that {@link PresentationOutput} takes on the GL thread a few times a second, and only while
 * this is on screen.
 */
@SuppressWarnings("serial")
final class PresenterPreview extends JComponent implements PresentationOutput.Sink {

    @Nullable private BufferedImage frame;

    PresenterPreview() {
        setPreferredSize(new Dimension(360, 202));
        // BoxLayout hands a child its MAXIMUM height (see buildPresenterWindow), so cap it.
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 240));
        setAlignmentX(LEFT_ALIGNMENT);
        setToolTipText("What the projector shows, refreshed " + PresentationOutput.PREVIEW_HZ + " times a second at most");
    }

    @Override
    public boolean wants() {
        return isShowing();
    }

    @Override
    public void accept(BufferedImage newFrame) {
        if (SwingUtilities.isEventDispatchThread())
            setFrame(newFrame);
        else
            SwingUtilities.invokeLater(() -> setFrame(newFrame));
    }

    private void setFrame(BufferedImage newFrame) {
        frame = newFrame;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, getWidth(), getHeight());
        BufferedImage f = frame;
        if (f == null)
            return;
        int[] box = fitInto(f.getWidth(), f.getHeight(), getWidth(), getHeight());
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(f, box[0], box[1], box[2], box[3], null);
        } finally {
            g2.dispose();
        }
    }

    /** x, y, width, height of an image of the given size fitted and centred in the box. */
    static int[] fitInto(int imageWidth, int imageHeight, int boxWidth, int boxHeight) {
        double scale = Math.min(boxWidth / (double) Math.max(1, imageWidth), boxHeight / (double) Math.max(1, imageHeight));
        int w = (int) Math.round(imageWidth * scale), h = (int) Math.round(imageHeight * scale);
        return new int[]{(boxWidth - w) / 2, (boxHeight - h) / 2, w, h};
    }

}
