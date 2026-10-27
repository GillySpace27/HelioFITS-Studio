package org.helioviewer.jhv.layers.filters;

import java.awt.Component;
import java.awt.FileDialog;
import java.io.File;

import javax.annotation.Nullable;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

import org.helioviewer.jhv.app.Settings;
import org.helioviewer.jhv.gui.MainFrame;
import org.helioviewer.jhv.image.ImageBuffer;
import org.helioviewer.jhv.image.ImageProcessingSettings;
import org.helioviewer.jhv.io.LascoBackground;
import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.view.View;

/**
 * The LASCO background: whether this layer takes it off, where it comes from, and what it is.
 *
 * <p>Studio subtracts NRL's monthly minimum image from every LASCO frame that has one
 * (FITSImage.readData), which changes what the picture is: the readout says "minus the NRL monthly
 * minimum background" instead of "includes the F corona". This makes that step visible and
 * switchable per layer, and shows the background itself for the frame on screen.
 *
 * <p>Where it comes from is one of four modes (LascoBackground.Mode): NRL's monthly images blended
 * in time (the default), the nearest one alone, or the user's own FITS file or dated pair, which
 * LocalBackground reads and refuses with a reason when it cannot be put in DN/s against the frame.
 *
 * <p>Rows: the switch with the preview button, the mode, one or two file rows for a local mode,
 * then what the frame on screen actually got.
 * Polled with the section badges, because whether a frame has a background is known only once it
 * has been decoded, and a provisional frame (fetch failed) is decided per frame.
 */
public final class BackgroundPanel {

    private final ImageLayer layer;
    private final JLabel title = new JLabel("Subtract ", JLabel.RIGHT);
    private final JCheckBox subtract = new JCheckBox(NRL);
    private final JButton preview = new JButton("Preview");
    private final JComboBox<LascoBackground.Mode> mode = new JComboBox<>(LascoBackground.Mode.values());
    private final FileRow fileA = new FileRow(true);
    private final FileRow fileB = new FileRow(false);
    private final JLabel status = new JLabel();
    private static final String NRL = "NRL monthly minimum image";
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
            dialog.showFor(request, choice());
        });
        mode.setToolTipText("<html>Interpolated: NRL's two monthly images around the frame, blended in time.<br>"
                + "Nearest: the one monthly image closest in time.<br>"
                + "Local file: one FITS file of your own, for every frame.<br>"
                + "Local pair: two dated FITS files of your own, blended by where the frame falls between their DATE-OBS.<br>"
                + "A local file is put in DN/s as the frame is, (BZERO + raw x BSCALE - OFFSET) / EXPTIME, and must be the frame's size.");
        mode.addActionListener(e -> {
            if (syncing)
                return;
            LascoBackground.Mode m = (LascoBackground.Mode) mode.getSelectedItem();
            if (m == null)
                return;
            LascoBackground.Choice c = choice();
            setChoice(layer, new LascoBackground.Choice(m, c.fileA(), c.fileB()));
            if (m.local() && c.fileA().isEmpty())
                fileA.choose(); // a local mode means nothing until a file is chosen, so ask now
        });
        update();
    }

    private LascoBackground.Choice choice() {
        return layer.getProcessingSettings().fitsParameters().background();
    }

    /**
     * Set where the background comes from on this layer and every other selected LASCO layer, and
     * turn the subtraction on: choosing a source is asking for it. Each layer that changed reads its
     * frames again, as for the switch.
     */
    public static void setChoice(ImageLayer origin, LascoBackground.Choice choice) {
        set(origin, true, choice);
    }

    /** The switch and the source together, for a revert. */
    public static void set(ImageLayer origin, boolean on, LascoBackground.Choice choice) {
        Layers.applyToSelectedLayers(origin, il -> {
            if (il != origin && !hasBackground(il))
                return;
            ImageProcessingSettings s = il.getProcessingSettings();
            if (s.fitsParameters().subtractBackground() == on && s.fitsParameters().background().equals(choice))
                return;
            s.setSubtractBackground(on);
            s.setBackground(choice);
            il.reloadSources();
        });
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

    /** Where the background comes from. */
    public FilterDetails modeRow() {
        JPanel filler = new JPanel();
        filler.setOpaque(false);
        return new Row(new JLabel("Source ", JLabel.RIGHT), mode, filler);
    }

    /** The local file, or the first of a pair; hidden for NRL modes. */
    public FilterDetails fileARow() {
        return fileA;
    }

    /** The second file of a pair; hidden otherwise. */
    public FilterDetails fileBRow() {
        return fileB;
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
        LascoBackground.Choice choice = choice();
        syncing = true;
        try {
            if (subtract.isSelected() != on)
                subtract.setSelected(on);
            if (mode.getSelectedItem() != choice.mode())
                mode.setSelectedItem(choice.mode());
        } finally {
            syncing = false;
        }
        String what = choice.mode().local() ? "Your own background" : NRL;
        if (!what.equals(subtract.getText()))
            subtract.setText(what);
        fileA.show(choice.mode().local(), choice.mode() == LascoBackground.Mode.LOCAL_PAIR ? "File 1 " : "File ", choice.fileA());
        fileB.show(choice.mode() == LascoBackground.Mode.LOCAL_PAIR, "File 2 ", choice.fileB());

        View.ImageData data = layer.getImageData();
        ImageBuffer buffer = data == null ? null : data.imageBuffer();
        request = buffer == null ? null : buffer.background();
        // Short, because the column is narrow; the tooltip says it in full.
        String text;
        String tip;
        String note = buffer == null ? null : buffer.backgroundNote();
        if (buffer == null || request == null) {
            text = "";
            tip = null;
        } else if (buffer.isBackgroundSubtracted() && note != null) {
            // Used, with something worth knowing: outside a pair's dates, or a different filter or roll.
            text = "Removed; " + note;
            tip = "This frame is the archive's minus the background, in DN per second. " + note;
        } else if (buffer.isBackgroundSubtracted()) {
            text = "Removed from this frame (DN/s)";
            tip = buffer.isBackgroundLocal() ? "This frame is the archive's minus your background, in DN per second"
                    : "This frame is the archive's minus the monthly minimum, in DN per second";
        } else if (on && note != null) {
            text = "Not used: " + note;
            tip = "Your background could not be used, so this frame is shown as served: " + note;
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
            dialog.follow(request, choice);
    }

    /** One file of a local background: its name, and the button that picks it. */
    private final class FileRow implements FilterDetails {
        private final boolean first;
        private final JLabel label = new JLabel("File ", JLabel.RIGHT);
        private final JLabel name = new JLabel();
        private final JButton pick = new JButton("Choose...");

        FileRow(boolean _first) {
            first = _first;
            pick.addActionListener(e -> choose());
        }

        void show(boolean visible, String title, String path) {
            label.setVisible(visible);
            name.setVisible(visible);
            pick.setVisible(visible);
            if (!title.equals(label.getText()))
                label.setText(title);
            String shown = path.isEmpty() ? "none chosen" : new File(path).getName();
            if (!shown.equals(name.getText())) {
                name.setText(shown);
                name.setToolTipText(path.isEmpty() ? null : path);
            }
        }

        void choose() {
            FileDialog dialog = new FileDialog(MainFrame.get(), first ? "Choose a background FITS file" : "Choose the second background FITS file", FileDialog.LOAD);
            LascoBackground.Choice c = choice();
            dialog.setMultipleMode(first && c.mode() == LascoBackground.Mode.LOCAL_PAIR); // both files of a pair at once
            String current = first ? c.fileA() : c.fileB();
            dialog.setDirectory(current.isEmpty() ? Settings.getProperty("path.local") : new File(current).getParent());
            dialog.setVisible(true);
            File[] files = dialog.getFiles();
            if (files.length == 0)
                return;
            String a = first ? files[0].getPath() : c.fileA();
            String b = first ? (files.length > 1 ? files[1].getPath() : c.fileB()) : files[0].getPath();
            setChoice(layer, new LascoBackground.Choice(c.mode(), a, b));
        }

        @Override
        public Component getFirst() {
            return label;
        }

        @Override
        public Component getSecond() {
            return name;
        }

        @Override
        public Component getThird() {
            return pick;
        }
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
