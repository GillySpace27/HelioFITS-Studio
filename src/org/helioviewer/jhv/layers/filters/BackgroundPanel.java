package org.helioviewer.jhv.layers.filters;

import java.awt.Component;

import javax.annotation.Nullable;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.io.LascoBackground;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.view.View;

/**
 * The LASCO monthly background: whether this layer takes it off, and what it is.
 *
 * <p>Studio subtracts NRL's monthly minimum image from every LASCO frame that has one
 * (FITSImage.readData), which changes what the picture is: the readout says "minus the NRL monthly
 * minimum background" instead of "includes the F corona". This makes that step visible and
 * switchable per layer, and shows the background itself for the frame on screen.
 *
 * <p>Two rows: the switch with the preview button, then what the frame on screen actually got.
 * Polled with the section badges, because whether a frame has a background is known only once it
 * has been decoded, and a provisional frame (fetch failed) is decided per frame.
 */
public final class BackgroundPanel {

    private final ImageLayer layer;
    private final JLabel title = new JLabel("Subtract ", JLabel.RIGHT);
    private final JCheckBox subtract = new JCheckBox("NRL monthly minimum image");
    private final JButton preview = new JButton("Preview");
    private final JLabel status = new JLabel();
    private boolean syncing;
    @Nullable
    private BackgroundPreview dialog;
    @Nullable
    private LascoBackground.Request request; // the frame on screen's, null until one has been decoded

    public BackgroundPanel(ImageLayer _layer) {
        layer = _layer;
        subtract.setToolTipText("<html>Take NRL's monthly minimum image off each frame (in DN per second), as the browse movies do.<br>"
                + "Off, frames are the archive's product as served, in DN. Changing it reads the frames again.");
        subtract.addActionListener(e -> {
            if (syncing)
                return;
            setSubtract(layer, subtract.isSelected());
        });
        preview.setToolTipText("Show the background for the frame on screen");
        preview.addActionListener(e -> {
            if (dialog == null)
                dialog = new BackgroundPreview();
            dialog.showFor(request);
        });
        update();
    }

    /**
     * Set the switch on this layer and on every other selected LASCO layer, as the other rows fan
     * out to a selection. A peer without a background is left alone: the flag means nothing to it,
     * and flipping it would only re-read its files.
     */
    public static void setSubtract(ImageLayer origin, boolean on) {
        Layers.applyToSelectedLayers(origin, il -> {
            if (il != origin && !hasBackground(il))
                return;
            if (il.getProcessingSettings().fitsParameters().subtractBackground() == on)
                return;
            il.getProcessingSettings().setSubtractBackground(on);
            il.reloadSources(); // the clip set is sampled off the subtracted pixels, as for a plane change
        });
    }

    private static boolean hasBackground(ImageLayer il) {
        View.ImageData data = il.getImageData();
        return data != null && data.imageBuffer().background() != null;
    }

    /** The switch, and the button that shows the background. */
    public FilterDetails switchRow() {
        return new Row(title, subtract, preview);
    }

    /** What the frame on screen got. */
    public FilterDetails statusRow() {
        JPanel filler = new JPanel();
        filler.setOpaque(false);
        return new Row(new JLabel(), status, filler);
    }

    /** True when the frame on screen has a published background, which is when this section applies. */
    public boolean applies() {
        return request != null;
    }

    /** Re-read from the layer: the switch, the frame on screen and, if open, the preview. */
    public void update() {
        boolean on = layer.getProcessingSettings().fitsParameters().subtractBackground();
        syncing = true;
        try {
            if (subtract.isSelected() != on)
                subtract.setSelected(on);
        } finally {
            syncing = false;
        }

        View.ImageData data = layer.getImageData();
        ImageBuffer buffer = data == null ? null : data.imageBuffer();
        request = buffer == null ? null : buffer.background();
        // Short, because the column is narrow; the tooltip says it in full.
        String text;
        String tip;
        if (buffer == null || request == null) {
            text = "";
            tip = null;
        } else if (buffer.isBackgroundSubtracted()) {
            text = "Removed from this frame (DN/s)";
            tip = "This frame is the archive's minus the blended monthly minimum, in DN per second";
        } else if (buffer.isProvisional()) {
            text = "Fetch failed; frame shown without it";
            tip = "The background could not be fetched from NRL, so this frame is shown as served; it is read again on the next request";
        } else if (!on) {
            text = "Off: frames as served, in DN";
            tip = "The archive's product as served: total brightness, F corona and stray light included (instruments/LASCO.md §2)";
        } else {
            text = "None applied to this frame";
            tip = "NRL lists no monthly image near this frame's date, or its header has no exposure time";
        }
        if (!text.equals(status.getText())) {
            status.setText(text);
            status.setToolTipText(tip);
        }
        preview.setEnabled(request != null);
        if (dialog != null)
            dialog.follow(request);
    }

    private record Row(Component first, Component second, Component third) implements FilterDetails {
        @Override
        public Component getFirst() {
            return first;
        }

        @Override
        public Component getSecond() {
            return second;
        }

        @Override
        public Component getThird() {
            return third;
        }
    }

}
