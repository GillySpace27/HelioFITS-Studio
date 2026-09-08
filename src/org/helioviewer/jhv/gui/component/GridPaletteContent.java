package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Component;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JPanel;

import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.layers.GridLayer;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.layers.selector.GridLayerOptions;

/**
 * The grid, Thomson sphere, celestial sphere, ecliptic and planet overlay settings as a floating
 * palette, next to Projection and HDR: the ONE place those settings exist.
 *
 * <p>The sidebar's Grid row keeps the master on/off checkbox and, when selected, points here
 * ({@code PalettePointer}); it no longer carries a second copy of these controls. The same
 * master toggle sits at the top of this palette, bound to the same {@link GridLayer#isEnabled()}
 * the row reads, so the palette is complete on its own: toggle plus settings, one click apart.
 * The settings stay live while the grid is off, since setting a grid up before showing it is a
 * legitimate thing to do and the toggle is right there.
 *
 * <p>There is exactly one {@link GridLayer} at a time, but not the same one forever: a session
 * restore replaces it. The panel is therefore rebuilt whenever {@link Layers#getGridLayer()}
 * stops being the instance the widgets were built against, the way {@code
 * SequencePaletteContent} follows its layer.
 */
final class GridPaletteContent {

    private static final JPanel panel = new JPanel(new BorderLayout());
    private static final JCheckBox showGrid = new JCheckBox("Show grid");
    private static boolean built;
    private static boolean syncing; // mirroring the layer into the checkbox, not the user ticking it

    @Nullable
    private static GridLayer boundLayer;
    @Nullable
    private static GridLayerOptions options;

    static Component build() {
        if (built) {
            refresh();
            return panel;
        }
        built = true;
        panel.setOpaque(false);
        showGrid.setOpaque(false);
        showGrid.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));
        showGrid.setToolTipText("The same switch as the Grid row's checkbox under Overlays in the sidebar");
        showGrid.addActionListener(e -> {
            if (syncing || boundLayer == null)
                return;
            boundLayer.setEnabled(showGrid.isSelected());
            Layers.fireLayerUpdated(boundLayer); // the sidebar's row follows
            DisplayController.render(1);
        });
        panel.add(showGrid, BorderLayout.PAGE_START);
        refresh();
        return panel;
    }

    /** Rebind if the grid layer was replaced, and mirror its on/off state into the checkbox. */
    static void refresh() {
        if (!built)
            return;
        GridLayer layer = Layers.getGridLayer();
        if (layer != boundLayer) {
            boundLayer = layer;
            if (options != null)
                panel.remove(options);
            options = layer == null ? null : new GridLayerOptions(layer);
            if (options != null)
                panel.add(options, BorderLayout.CENTER);
            panel.revalidate();
            panel.repaint();
        }
        boolean on = layer != null && layer.isEnabled();
        showGrid.setEnabled(layer != null);
        if (showGrid.isSelected() != on) {
            syncing = true;
            try {
                showGrid.setSelected(on);
            } finally {
                syncing = false;
            }
        }
    }

    static {
        Layers.addListener(new Layers.Listener() {
            @Override
            public void layerAdded(int index, Layer layer) {
                if (layer instanceof GridLayer)
                    refresh();
            }

            @Override
            public void layerRemoved(int index, Layer layer) {
                if (layer instanceof GridLayer)
                    refresh();
            }

            @Override
            public void layersCleared() {
                refresh(); // a session restore: the grid layer is a new instance
            }

            @Override
            public void nameUpdated(Layer layer) {
            }

            @Override
            public void layerUpdated(Layer layer) {
                if (layer instanceof GridLayer)
                    refresh(); // the row's checkbox was ticked
            }

            @Override
            public void timeUpdated(Layer layer) {
            }
        });
    }

    private GridPaletteContent() {}

}
