package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;

import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.layers.filters.FilterDetails;
import org.helioviewer.jhv.layers.filters.ImageFilterPanel;
import org.helioviewer.jhv.layers.filters.SequencePanel;

/**
 * The sequence filter as a floating palette, next to Projection, instead of a dropdown in a layer
 * row.
 *
 * <p>A velocity filter or the noise gate runs over every frame and takes minutes on a large movie,
 * so its settings, its readout and its progress are things to watch while the view plays. That is
 * what the palette form is for, and what a popup that closes on focus loss is not.
 *
 * <p>Unlike Projection this is a per-layer setting, so the palette needs to say whose filter it is
 * editing, and lets that be chosen rather than only reported: the combo at the top follows the
 * active image layer until the user picks a different one here, at which point it stays on that
 * choice regardless of which layer is active elsewhere, until they pick again or that layer is
 * removed. It builds its own SequencePanel rather than borrowing the one in the layer row: a
 * Swing component has exactly one parent, and both read their state back from the layer, so the
 * two stay in step without having to talk to each other.
 *
 * <p>RHEF lives here too, as the palette's first section, which is why it is called Filters. Two reasons
 * it belongs beside the Fourier filter rather than only in the Image Layers row. Setting up a session
 * meant opening Image Layers again just for that one control. And the two filters act on each other: a
 * sequence filter takes the per-frame filter to None, which used to happen in a panel nobody was
 * looking at. The Image Layers row keeps its copy, since that is where the colour table, levels and
 * contrast RHEF works together with are; both copies are bound to the one setting on the layer.
 */
final class SequencePaletteContent {

    private static final JPanel panel = new JPanel(new BorderLayout());
    private static final JComboBox<ImageLayer> layerCombo = new JComboBox<>();
    private static final JLabel emptyLabel = new JLabel("No image layer");
    private static final JPanel body = new JPanel(); // Per frame, then Whole movie; rebuilt per bound layer
    private static boolean built;
    private static boolean syncing; // rebuilding the combo's own items/selection, not a user pick

    // The user's own choice from the combo, kept regardless of what becomes the active layer
    // elsewhere, until it is removed. Null means "follow the active layer", which is also the
    // starting state and what a plain readout always did.
    @Nullable
    private static ImageLayer explicitLayer;
    private static List<ImageLayer> lastLayers = List.of();

    @Nullable
    private static ImageLayer boundLayer;
    private static boolean boundOnce; // false until the first refresh(), so a null target still binds an empty state
    @Nullable
    private static SequencePanel sequencePanel;
    @Nullable
    private static ImageFilterPanel filterPanel;

    static Component build() {
        panel.removeAll();
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        layerCombo.setBorder(BorderFactory.createEmptyBorder(2, 2, 4, 2));
        layerCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean hasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, hasFocus);
                if (value instanceof ImageLayer layer)
                    setText(layer.getName());
                return this;
            }
        });
        layerCombo.addActionListener(e -> {
            if (syncing)
                return;
            if (layerCombo.getSelectedItem() instanceof ImageLayer picked)
                explicitLayer = picked;
            refresh();
        });
        panel.add(layerCombo, BorderLayout.PAGE_START);
        body.removeAll();
        body.setLayout(new BoxLayout(body, BoxLayout.PAGE_AXIS));
        body.setOpaque(false);
        panel.add(body, BorderLayout.CENTER);
        lastLayers = List.of(); // force the combo to be populated fresh under the new owner
        boundLayer = null; // the palette may have been rebuilt under a new owner: rebind
        boundOnce = false;
        sequencePanel = null;
        filterPanel = null;
        built = true;
        refresh();
        return panel;
    }

    /** Bind to this layer, as the layer row's "Open" asks: the same as picking it in the combo. */
    static void show(ImageLayer layer) {
        explicitLayer = layer;
        if (built)
            refresh();
    }

    /** Follow the active layer unless the user picked one here, and mirror its state into the widgets. */
    static void refresh() {
        if (!built)
            return;

        List<ImageLayer> current = Layers.getImageLayers();
        if (!current.equals(lastLayers)) {
            // A copy. Layers.getImageLayers() is a live subList view, and once the layer list changes, any
            // use of an old view throws ConcurrentModificationException. Holding the view itself made
            // the next add or remove throw from inside Layers' listener loop, which stranded the new
            // layer at "Loading..." (ImageLayer.create never reached load) and stopped New Session
            // after its first removal. Gilly's report, 2026-09-14.
            lastLayers = List.copyOf(current);
            syncing = true;
            try {
                layerCombo.removeAllItems();
                for (ImageLayer layer : current)
                    layerCombo.addItem(layer);
            } finally {
                syncing = false;
            }
        }

        if (explicitLayer != null && !current.contains(explicitLayer))
            explicitLayer = null; // the chosen layer is gone: fall back to following the active one
        ImageLayer target = explicitLayer != null ? explicitLayer : Layers.getActiveImageLayer();

        if (layerCombo.getSelectedItem() != target) {
            syncing = true;
            try {
                layerCombo.setSelectedItem(target);
            } finally {
                syncing = false;
            }
        }
        layerCombo.setEnabled(!current.isEmpty());

        if (target != boundLayer || !boundOnce) {
            boundLayer = target;
            boundOnce = true;
            sequencePanel = target == null ? null : new SequencePanel(target);
            // Only for a layer with pixels. While a session restores, the active layer is the registry's
            // placeholder, which has no frame yet, and ImageFilterPanel reads its enhance and Υ values
            // straight off it: the first launch with RHEF in here threw on exactly that. Throwing here
            // also left boundLayer set, so the palette would not have rebuilt until the layer changed.
            filterPanel = target == null || !target.hasPixels() ? null : new ImageFilterPanel(target);
            // Deliberately no setPreferredSize here. Pinning the height froze the palette at
            // whatever the readout said when it was built, and the readout gains two lines the
            // moment a kind is chosen: the last line and the run button under it ended up outside
            // the window. The content sizes itself and the window is repacked when it changes.
            body.removeAll();
            if (sequencePanel == null || filterPanel == null)
                body.add(left(emptyLabel));
            else {
                body.add(heading("Per frame"));
                body.add(row(filterPanel));
                body.add(heading("Whole movie"));
                body.add(left(sequencePanel.getPaletteContent()));
            }
            panel.revalidate();
            panel.repaint();
        }
        if (sequencePanel != null && boundLayer != null)
            sequencePanel.refresh(boundLayer);
        if (filterPanel != null && boundLayer != null) {
            filterPanel.syncFromLayer(boundLayer); // RHEF may have been changed from the Image Layers row
            // Greyed for a categorical colour table, as the Image Layers row greys it: a table that promises
            // each value is exactly one colour is broken by anything that remaps values, RHEF included.
            boolean categorical = org.helioviewer.jhv.image.lut.LUTLabels.isCategorical(boundLayer.getDisplaySettings().getLUT());
            for (Component part : new Component[]{filterPanel.getFirst(), filterPanel.getSecond(), filterPanel.getThird()})
                org.helioviewer.jhv.gui.ComponentUtils.setEnabled(part, !categorical);
            if (filterPanel.getFirst() instanceof javax.swing.JComponent label)
                label.setToolTipText(categorical
                        ? "Disabled: this layer's colours are a fixed category legend, not a value range to adjust" : null);
        }
        Palette.repackAll(); // the readout gains and loses lines; the window has to follow
    }

    static {
        // A colour table change fires no layer event (see LUTPanel.addLutListener for why), and it is what
        // flips whether RHEF may be used on this layer.
        org.helioviewer.jhv.layers.filters.LUTPanel.addLutListener(SequencePaletteContent::refresh);
        // The palette is not modal and the layer selection changes underneath it, so it has to be
        // told. This also catches a filter finishing, which is what moves the progress bar and
        // clears the status line.
        Layers.addListener(new Layers.Listener() {
            @Override
            public void layerAdded(int index, Layer layer) {
                refresh();
            }

            @Override
            public void layerRemoved(int index, Layer layer) {
                refresh();
            }

            @Override
            public void layersCleared() {
                explicitLayer = null;
                refresh();
            }

            @Override
            public void nameUpdated(Layer layer) {
                if (built)
                    layerCombo.repaint(); // display text only; the layer's identity has not changed
            }

            @Override
            public void layerUpdated(Layer layer) {
                refresh();
            }

            @Override
            public void timeUpdated(Layer layer) {
            }
        });
    }

    /** A section title inside the palette: smaller than the palette's own header, a step above the controls. */
    private static JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBorder(BorderFactory.createEmptyBorder(8, 2, 3, 2));
        return left(label);
    }

    /** One filter's three parts on a line, as FilterRowLayout lays them out in the Image Layers row. */
    @SuppressWarnings("serial")
    private static JPanel row(FilterDetails details) {
        JPanel row = new JPanel(new BorderLayout(4, 0)) {
            @Override
            public Dimension getMaximumSize() { // a BoxLayout stretches a row to fill the height otherwise
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        row.setOpaque(false);
        row.add(details.getFirst(), BorderLayout.LINE_START);
        row.add(details.getSecond(), BorderLayout.CENTER);
        row.add(details.getThird(), BorderLayout.LINE_END);
        return left(row);
    }

    private static <T extends javax.swing.JComponent> T left(T c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT); // a BoxLayout mixes alignments into a staircase otherwise
        return c;
    }

    private SequencePaletteContent() {}

}
