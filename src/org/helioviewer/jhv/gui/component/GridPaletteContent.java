package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Component;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

import org.helioviewer.jhv.layers.GridLayer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.layers.selector.GridLayerOptions;

/**
 * The grid, Thomson sphere, celestial sphere, ecliptic and planet overlay settings as a floating
 * palette, next to Projection and HDR, instead of only reachable by opening the Grid row in the
 * layer list.
 *
 * <p>There is exactly one {@link GridLayer} in the whole app (a default layer, always present),
 * so unlike the sequence filter this has no layer to follow: it builds one {@link
 * GridLayerOptions} against that layer and keeps it for the life of the palette. It builds its
 * own instance rather than borrowing the layer row's, for the reason given on {@link
 * GridLayerOptions}'s own constructor.
 */
final class GridPaletteContent {

    private static final JPanel panel = new JPanel(new BorderLayout());
    private static boolean built;

    static Component build() {
        if (built)
            return panel;
        built = true;
        panel.setOpaque(false);
        GridLayer layer = Layers.getGridLayer();
        if (layer == null) { // should not happen: a default layer, added before any UI can ask for it
            panel.add(new JLabel("Grid layer not available", SwingConstants.CENTER), BorderLayout.CENTER);
        } else {
            panel.add(new GridLayerOptions(layer), BorderLayout.CENTER);
        }
        return panel;
    }

    static void refresh() {
        // Every widget in GridLayerOptions binds straight to the live layer through its own
        // listeners (see that class), so there is nothing external to pull in here; this exists
        // only so the palette's onShow has something to call, matching the other palettes' shape.
    }

    private GridPaletteContent() {}

}
