package org.helioviewer.jhv.gui.component;

import java.awt.BorderLayout;
import java.awt.Component;

import javax.annotation.Nullable;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JPanel;

import org.helioviewer.jhv.display.DisplayController;
import org.helioviewer.jhv.layers.Layer;
import org.helioviewer.jhv.layers.Layers;
import org.helioviewer.jhv.layers.ViewpointLayer;
import org.helioviewer.jhv.layers.selector.ViewpointLayerOptionsPanel;

/**
 * Where the scene is seen from (Free, Follow, Overview) and how the camera moves within that
 * (revolving), as a floating palette next to Grid: the ONE place those settings exist.
 *
 * <p>They are the Viewpoint layer's options, and the sidebar's Camera section kept them until now.
 * That row keeps its master checkbox and points here ({@code PalettePointer}); the same checkbox
 * sits at the top of this palette, bound to the same {@link ViewpointLayer#isEnabled()}. Off, the
 * view returns to the observer and the behaviours release the camera, which is what the row's
 * checkbox has always done. It does NOT reach the revolution, which moves the camera rather than
 * choosing a vantage point and runs either way (see ViewpointLayerOptions.revolve). The settings
 * stay live while it is off, so a behaviour can be set up before the layer takes the camera.
 *
 * <p>One home matters more here than for the grid: {@code ViewpointLayerOptionsPanel} registers
 * itself as the single panel {@code ViewpointLayerOptions.refreshPanel()} pokes, so a second live
 * instance would leave one of them stale. A session restore replaces the layer, which restore
 * signals as layersCleared; the panel is rebuilt against the new one then.
 */
final class CameraPaletteContent {

    private static final JPanel panel = new JPanel(new BorderLayout());
    private static final JCheckBox cameraOn = new JCheckBox("Drive the viewpoint");
    private static boolean built;
    private static boolean syncing;

    @Nullable
    private static ViewpointLayer boundLayer;
    @Nullable
    private static ViewpointLayerOptionsPanel options;

    static Component build() {
        if (built) {
            refresh();
            return panel;
        }
        built = true;
        panel.setOpaque(false);
        cameraOn.setOpaque(false);
        cameraOn.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));
        cameraOn.setToolTipText("The same switch as the Camera row's checkbox in the sidebar. Off, the view is the "
                + "observer's and the Seen-from behaviours below release the camera. Revolving is not affected: "
                + "it moves the camera rather than deciding where the scene is seen from, and runs either way.");
        cameraOn.addActionListener(e -> {
            if (syncing || boundLayer == null)
                return;
            boundLayer.setEnabled(cameraOn.isSelected());
            Layers.fireLayerUpdated(boundLayer); // the sidebar's row follows
            DisplayController.render(1);
        });
        panel.add(cameraOn, BorderLayout.PAGE_START);
        refresh();
        return panel;
    }

    /** Rebind if the viewpoint layer was replaced, and mirror its on/off state into the checkbox. */
    static void refresh() {
        if (!built)
            return;
        ViewpointLayer layer = Layers.getViewpointLayer();
        if (layer != boundLayer) {
            boundLayer = layer;
            if (options != null)
                panel.remove(options);
            options = layer == null ? null : new ViewpointLayerOptionsPanel(layer);
            if (options != null)
                panel.add(options, BorderLayout.CENTER);
            panel.revalidate();
            panel.repaint();
        }
        boolean on = layer != null && layer.isEnabled();
        cameraOn.setEnabled(layer != null);
        if (cameraOn.isSelected() != on) {
            syncing = true;
            try {
                cameraOn.setSelected(on);
            } finally {
                syncing = false;
            }
        }
    }

    static {
        Layers.addListener(new Layers.Listener() {
            @Override
            public void layerAdded(int index, Layer layer) {
                if (layer instanceof ViewpointLayer)
                    refresh();
            }

            @Override
            public void layerRemoved(int index, Layer layer) {
                if (layer instanceof ViewpointLayer)
                    refresh();
            }

            @Override
            public void layersCleared() {
                refresh(); // a session restore: the viewpoint layer is a new instance
            }

            @Override
            public void nameUpdated(Layer layer) {
            }

            @Override
            public void layerUpdated(Layer layer) {
                if (layer instanceof ViewpointLayer)
                    refresh(); // the row's checkbox was ticked
            }

            @Override
            public void timeUpdated(Layer layer) {
            }
        });
    }

    private CameraPaletteContent() {}

}
