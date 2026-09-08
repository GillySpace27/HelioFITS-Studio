package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Component;
import java.util.List;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;

import org.helioviewer.jhv.layers.ImageLayer;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;
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
 */
final class SequencePaletteContent {

    private static final JPanel panel = new JPanel(new BorderLayout());
    private static final JComboBox<ImageLayer> layerCombo = new JComboBox<>();
    private static final JLabel emptyLabel = new JLabel("No image layer");
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
        lastLayers = List.of(); // force the combo to be populated fresh under the new owner
        boundLayer = null; // the palette may have been rebuilt under a new owner: rebind
        boundOnce = false;
        sequencePanel = null;
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
            lastLayers = current;
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
            panel.remove(sequencePanel != null ? sequencePanel.getPaletteContent() : emptyLabel);
            sequencePanel = target == null ? null : new SequencePanel(target);
            // Deliberately no setPreferredSize here. Pinning the height froze the palette at
            // whatever the readout said when it was built, and the readout gains two lines the
            // moment a kind is chosen: the last line and the run button under it ended up outside
            // the window. The content sizes itself and the window is repacked when it changes.
            panel.add(sequencePanel != null ? sequencePanel.getPaletteContent() : emptyLabel, BorderLayout.CENTER);
            panel.revalidate();
            panel.repaint();
        }
        if (sequencePanel != null && boundLayer != null)
            sequencePanel.refresh(boundLayer);
        Palette.repackAll(); // the readout gains and loses lines; the window has to follow
    }

    static {
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

    private SequencePaletteContent() {}

}
