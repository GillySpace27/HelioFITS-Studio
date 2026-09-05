package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Component;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
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
 * <p>Unlike Projection this is a per-layer setting, so the palette follows the active image layer
 * and says whose filter it is editing. It builds its own SequencePanel rather than borrowing the
 * one in the layer row: a Swing component has exactly one parent, and both read their state back
 * from the layer, so the two stay in step without having to talk to each other.
 */
final class SequencePaletteContent {

    private static final JPanel panel = new JPanel(new BorderLayout());
    private static final JLabel layerLabel = new JLabel();
    private static boolean built;

    @Nullable
    private static ImageLayer boundLayer;
    @Nullable
    private static SequencePanel sequencePanel;

    static Component build() {
        panel.removeAll();
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        layerLabel.setBorder(BorderFactory.createEmptyBorder(2, 2, 4, 2));
        panel.add(layerLabel, BorderLayout.PAGE_START);
        boundLayer = null; // the palette may have been rebuilt under a new owner: rebind
        sequencePanel = null;
        built = true;
        refresh();
        return panel;
    }

    /** Follow the active layer, and mirror its current state into the widgets. */
    static void refresh() {
        if (!built)
            return;
        ImageLayer active = Layers.getActiveImageLayer();
        if (active != boundLayer) {
            boundLayer = active;
            if (sequencePanel != null)
                panel.remove(sequencePanel.getPaletteContent());
            sequencePanel = active == null ? null : new SequencePanel(active);
            // Deliberately no setPreferredSize here. Pinning the height froze the palette at
            // whatever the readout said when it was built, and the readout gains two lines the
            // moment a kind is chosen: the last line and the run button under it ended up outside
            // the window. The content sizes itself and the window is repacked when it changes.
            if (sequencePanel != null)
                panel.add(sequencePanel.getPaletteContent(), BorderLayout.CENTER);
            panel.revalidate();
            panel.repaint();
        }
        layerLabel.setText(active == null ? "No image layer" : "Layer: " + active.getName());
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
                refresh();
            }

            @Override
            public void nameUpdated(Layer layer) {
                refresh();
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
