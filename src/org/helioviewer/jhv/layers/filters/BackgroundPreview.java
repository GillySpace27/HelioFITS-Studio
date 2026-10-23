package org.helioviewer.jhv.layers.filters;

import java.awt.BorderLayout;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.SwingWorker;

import org.helioviewer.jhv.app.Log;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.io.LascoBackground;
import org.helioviewer.jhv.time.TimeUtils;

/**
 * The LASCO monthly background for the frame on screen, as the layer subtracts it.
 *
 * <p>The same array FITSImage takes off the pixels (LascoBackground.perSecond: the two monthly
 * images bracketing the frame, blended in time, in DN per second), drawn in grey with a fixed
 * display stretch. Rows are drawn as the frame's are, bottom row first in the file, and the image
 * is not rotated to solar north. Follows the layer's frame while it is open.
 */
@SuppressWarnings("serial")
final class BackgroundPreview extends JDialog {

    private static final int SIZE = 512;
    private static final double LOW = 0.005;
    private static final double HIGH = 0.995;
    private static final double GAMMA = 1 / 2.2;

    private final JLabel picture = new JLabel("", JLabel.CENTER);
    private final JLabel caption = new JLabel();
    @Nullable
    private LascoBackground.Request shown;
    @Nullable
    private LascoBackground.Request pending;
    private boolean rendering;

    BackgroundPreview() {
        super(MainFrame.get(), "LASCO background", false);
        picture.setPreferredSize(new java.awt.Dimension(SIZE, SIZE));
        caption.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
        getContentPane().add(picture, BorderLayout.CENTER);
        getContentPane().add(caption, BorderLayout.PAGE_END);
        pack();
        setLocationRelativeTo(MainFrame.get());
    }

    void showFor(@Nullable LascoBackground.Request request) {
        shown = null; // a press of the button always draws again
        follow(request);
        setVisible(true);
    }

    /** Draw this frame's background if it is not the one already drawn; ignored while closed. */
    void follow(@Nullable LascoBackground.Request request) {
        if (request == null || request.equals(shown) || (!isVisible() && shown != null))
            return;
        shown = request;
        if (rendering) {
            pending = request;
            return;
        }
        render(request);
    }

    private record Result(@Nullable BufferedImage image, String caption) {}

    private void render(LascoBackground.Request request) {
        rendering = true;
        caption.setText("Fetching the background for " + TimeUtils.format(request.milli()) + "...");
        new SwingWorker<Result, Void>() {
            @Override
            protected Result doInBackground() throws Exception { // a failed listing fails the caption, reported in done()
                String head = request.detector() + " " + request.filter() + "/" + request.polar() + ", frame at " + TimeUtils.format(request.milli());
                float[] background;
                try {
                    background = request.perSecond();
                } catch (LascoBackground.Unavailable e) {
                    return new Result(null, html(head, "Could not be fetched from NRL just now; the frame is shown without it."));
                }
                if (background == null)
                    return new Result(null, html(head, "NRL publishes no monthly image for this product and time."));
                float[] range = stretch(background);
                String files = request.describe();
                return new Result(draw(background, request.width(), request.height(), range[0], range[1]),
                        html(head, (files.isEmpty() ? "" : "From " + files + ".<br>")
                                + String.format("DN/s; grey runs %.3g to %.3g (0.5 to 99.5 percentile, gamma 2.2). Detector rows, not rotated to north.",
                                range[0], range[1])));
            }

            @Override
            protected void done() {
                rendering = false;
                try {
                    Result r = get();
                    picture.setIcon(r.image() == null ? null : new ImageIcon(r.image()));
                    picture.setText(r.image() == null ? "No background" : "");
                    caption.setText(r.caption());
                } catch (InterruptedException | ExecutionException e) {
                    Log.warn("LASCO background preview failed", e);
                    caption.setText("The background could not be drawn: " + e.getMessage());
                }
                pack();
                LascoBackground.Request next = pending;
                pending = null;
                if (next != null && next.equals(shown))
                    render(next);
            }
        }.execute();
    }

    private static String html(String head, String body) {
        return "<html><b>" + head + "</b><br>" + body + "</html>";
    }

    /** The 0.5 and 99.5 percentiles of the finite values, from a sample of at most a quarter million. */
    static float[] stretch(float[] values) {
        int step = Math.max(1, values.length / 262144);
        float[] sample = new float[(values.length + step - 1) / step];
        int n = 0;
        for (int i = 0; i < values.length; i += step)
            if (Float.isFinite(values[i]))
                sample[n++] = values[i];
        if (n == 0)
            return new float[]{0, 1};
        Arrays.sort(sample, 0, n);
        float lo = sample[(int) Math.floor(LOW * (n - 1))];
        float hi = sample[(int) Math.ceil(HIGH * (n - 1))];
        return new float[]{lo, hi > lo ? hi : lo + 1};
    }

    /** Grey, the file's last row at the top as the layer draws it, scaled to fit SIZE. */
    static BufferedImage draw(float[] values, int width, int height, float lo, float hi) {
        BufferedImage full = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] out = ((java.awt.image.DataBufferByte) full.getRaster().getDataBuffer()).getData();
        double span = hi - lo;
        for (int j = 0; j < height; j++) {
            int row = width * (height - 1 - j);
            for (int i = 0; i < width; i++) {
                float v = values[width * j + i];
                double t = Float.isFinite(v) ? Math.clamp((v - lo) / span, 0, 1) : 0;
                out[row + i] = (byte) Math.round(255 * Math.pow(t, GAMMA));
            }
        }
        double scale = Math.min(1, SIZE / (double) Math.max(width, height));
        if (scale >= 1)
            return full;
        int w = (int) Math.round(width * scale);
        int h = (int) Math.round(height * scale);
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        java.awt.Graphics2D g = small.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(full.getScaledInstance(w, h, Image.SCALE_AREA_AVERAGING), 0, 0, null);
        g.dispose();
        return small;
    }

}
